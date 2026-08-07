package com.oneday.airline.batch;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.events.FlightEventProducer;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.service.impl.FlightReassignmentService;
import com.oneday.airline.service.provider.FlightProviderPort;
import com.oneday.common.kafka.enums.FlightReassignReason;
import com.oneday.common.kafka.events.flight.FlightTimeChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The heartbeat behind live tracking and the reassignment engine (§8, §9), mirroring {@code
 * routing.NightlyRoutePlanJob}'s scheduled-job idiom. Every {@link AirlineProperties#getStatusPollDelayMs()}
 * (default 5 min), for every booked-but-not-landed flight:
 * <ul>
 *   <li>flips real DEPARTED/LANDED once the flight's own departure/arrival Instant has passed,
 *       notifying every parcel on it (the first real callers of {@link FlightEventProducer});</li>
 *   <li>asks the (simulated) vendor for its current word on the flight — a delay/cancellation past
 *       {@link AirlineProperties#getDelayReassignThresholdMinutes()} triggers a real reassignment; a
 *       milder delay is just an advisory time-changed notice.</li>
 * </ul>
 */
@Component
class FlightStatusPollJob {

    private static final Logger log = LoggerFactory.getLogger(FlightStatusPollJob.class);

    private final FlightInstanceRepository flightInstanceRepository;
    private final AwbRepository awbRepository;
    private final AwbParcelRepository awbParcelRepository;
    private final FlightProviderPort flightProviderPort;
    private final FlightEventProducer flightEventProducer;
    private final FlightReassignmentService flightReassignmentService;
    private final AirlineProperties properties;
    private final Clock clock;

    FlightStatusPollJob(FlightInstanceRepository flightInstanceRepository, AwbRepository awbRepository,
                         AwbParcelRepository awbParcelRepository, FlightProviderPort flightProviderPort,
                         FlightEventProducer flightEventProducer, FlightReassignmentService flightReassignmentService,
                         AirlineProperties properties, Clock clock) {
        this.flightInstanceRepository = flightInstanceRepository;
        this.awbRepository = awbRepository;
        this.awbParcelRepository = awbParcelRepository;
        this.flightProviderPort = flightProviderPort;
        this.flightEventProducer = flightEventProducer;
        this.flightReassignmentService = flightReassignmentService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${airline.status-poll-delay-ms:300000}")
    public void run() {
        List<FlightInstance> instances = flightInstanceRepository.findByStatusIn(
                List.of(FlightInstanceStatus.SCHEDULED, FlightInstanceStatus.DEPARTED));
        for (FlightInstance instance : instances) {
            try {
                processInstance(instance);
            } catch (Exception e) {
                log.error("FlightStatusPollJob failed for flight {} ({})", instance.getFlightNo(),
                        instance.getFlightDate(), e);
            }
        }
    }

    // Not @Transactional: called via self-invocation from run() in the same bean, where Spring's AOP
    // proxy wouldn't apply anyway. Each repository save() is already atomic on its own (Spring Data
    // JPA default); the one place multi-step atomicity matters (reassign) is a separate, correctly-
    // proxied bean, and events are intentionally published best-effort after the DB write commits
    // (matches RabbitEventPublisher's documented "never break an already-committed flow" rule).
    void processInstance(FlightInstance instance) {
        Instant now = clock.instant();

        if (instance.getStatus() == FlightInstanceStatus.SCHEDULED && !now.isBefore(instance.getDeparture())) {
            instance.setStatus(FlightInstanceStatus.DEPARTED);
            flightInstanceRepository.save(instance);
            notifyParcels(instance, flightEventProducer::emitDeparted);
        }
        if (instance.getStatus() == FlightInstanceStatus.DEPARTED && !now.isBefore(instance.getArrival())) {
            instance.setStatus(FlightInstanceStatus.LANDED);
            flightInstanceRepository.save(instance);
            notifyParcels(instance, flightEventProducer::emitLanded);
            return;   // landed — nothing left to reassign
        }

        FlightProviderPort.FlightStatusResult status =
                flightProviderPort.status(instance.getFlightNo(), instance.getFlightDate());

        if (status.status() == FlightProviderPort.FlightRealWorldStatus.CANCELLED) {
            reassignBookedAwbs(instance, FlightReassignReason.CANCELLATION);
            instance.setStatus(FlightInstanceStatus.CANCELLED);
            flightInstanceRepository.save(instance);
        } else if (status.status() == FlightProviderPort.FlightRealWorldStatus.DELAYED) {
            long delayMinutes = Duration.between(instance.getDeparture(), status.estimatedDeparture()).toMinutes();
            if (delayMinutes > properties.getDelayReassignThresholdMinutes()) {
                reassignBookedAwbs(instance, FlightReassignReason.DELAY);
            } else {
                Instant newCutoff = status.estimatedDeparture().minusSeconds(properties.getGateCutoffLeadMinutes() * 60L);
                instance.setDeparture(status.estimatedDeparture());
                instance.setArrival(status.estimatedArrival());
                instance.setCutoff(newCutoff);
                flightInstanceRepository.save(instance);
                flightEventProducer.emitTimeChanged(new FlightTimeChangedEvent(instance.getFlightNo(),
                        instance.getFlightDate(), instance.getDestHub(), status.estimatedDeparture(), newCutoff));
            }
        }
    }

    private void reassignBookedAwbs(FlightInstance instance, FlightReassignReason reason) {
        List<Awb> booked = bookedAwbsFor(instance);
        for (Awb awb : booked) {
            try {
                flightReassignmentService.reassign(awb, reason);
            } catch (Exception e) {
                log.error("Reassignment failed for AWB {} (bag {})", awb.getId(), awb.getBagId(), e);
            }
        }
    }

    private void notifyParcels(FlightInstance instance, Consumer<UUID> emit) {
        bookedAwbsFor(instance).stream()
                .flatMap(awb -> awbParcelRepository.findByAwbId(awb.getId()).stream())
                .forEach(parcel -> emit.accept(parcel.getParcelId()));
    }

    private List<Awb> bookedAwbsFor(FlightInstance instance) {
        return awbRepository.findByFlightNoAndFlightDate(instance.getFlightNo(), instance.getFlightDate()).stream()
                .filter(awb -> awb.getStatus() == AwbStatus.BOOKED)
                .toList();
    }
}
