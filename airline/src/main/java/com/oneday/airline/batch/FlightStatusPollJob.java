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
 * Two scheduled halves, split by cost and cadence (mirroring the user's "two crons" model):
 *
 * <ul>
 *   <li><b>Progress</b> — {@link #pollProgress()} runs frequently (every {@link
 *       AirlineProperties#getStatusPollDelayMs()}, default 5 min) and only flips real DEPARTED/LANDED
 *       once the flight's own departure/arrival Instant has passed, notifying every parcel on it. This
 *       is <em>clock-based</em>: it never calls the flight-data vendor, so it costs nothing and keeps
 *       live tracking fresh.</li>
 *   <li><b>Disruption</b> — {@link #pollImminent()} / {@link #pollUpcoming()} ask the vendor for its
 *       current word (the only calls that spend paid units), on a <em>tiered</em> cadence keyed on
 *       time-to-departure: flights in the last few hours before departure are swept every ~30 min so a
 *       cancellation is caught while there's still time to rebook before cutoff; flights further out are
 *       swept every ~3 h; far-out flights aren't polled at all. A cancellation or a delay past {@link
 *       AirlineProperties#getDelayReassignThresholdMinutes()} triggers a real reassignment; a milder
 *       delay is just an advisory time-changed notice.</li>
 * </ul>
 */
@Component
public class FlightStatusPollJob {

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

    /** Frequent, clock-only: advance DEPARTED/LANDED for every in-flight instance. No vendor calls. */
    @Scheduled(fixedDelayString = "${airline.status-poll-delay-ms:300000}")
    public void pollProgress() {
        List<FlightInstance> instances = flightInstanceRepository.findByStatusIn(
                List.of(FlightInstanceStatus.SCHEDULED, FlightInstanceStatus.DEPARTED));
        for (FlightInstance instance : instances) {
            try {
                advanceProgress(instance);
            } catch (Exception e) {
                log.error("Progress poll failed for flight {} ({})", instance.getFlightNo(), instance.getFlightDate(), e);
            }
        }
    }

    /** Imminent tier (paid): flights departing within the next few hours, swept every ~30 min. */
    @Scheduled(cron = "${airline.disruption-imminent-cron:0 */30 * * * *}", zone = "Asia/Kolkata")
    public void pollImminent() {
        pollWindow(0, properties.getDisruptionImminentWindowHours());
    }

    /** Upcoming tier (paid): flights a bit further out, swept every ~3 h. Disjoint from the imminent window. */
    @Scheduled(cron = "${airline.disruption-upcoming-cron:0 0 */3 * * *}", zone = "Asia/Kolkata")
    public void pollUpcoming() {
        pollWindow(properties.getDisruptionImminentWindowHours(), properties.getDisruptionUpcomingWindowHours());
    }

    /** Vendor-check every still-scheduled flight departing in [now+fromHours, now+toHours]. */
    private void pollWindow(int fromHours, int toHours) {
        Instant now = clock.instant();
        List<FlightInstance> instances = flightInstanceRepository.findByStatusInAndDepartureBetween(
                List.of(FlightInstanceStatus.SCHEDULED),
                now.plus(Duration.ofHours(fromHours)), now.plus(Duration.ofHours(toHours)));
        for (FlightInstance instance : instances) {
            try {
                checkDisruption(instance);
            } catch (Exception e) {
                log.error("Disruption poll failed for flight {} ({})", instance.getFlightNo(), instance.getFlightDate(), e);
            }
        }
    }

    // Not @Transactional: each repository save() is atomic on its own; the one place multi-step atomicity
    // matters (reassign) is a separate, correctly-proxied bean, and events publish best-effort after commit.

    /** Clock-only progress: SCHEDULED→DEPARTED at departure, DEPARTED→LANDED at arrival. Never calls the vendor. */
    void advanceProgress(FlightInstance instance) {
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
        }
    }

    /** Vendor-backed disruption handling for a still-scheduled flight (cancellation → reassign, delay → reassign/advisory). */
    void checkDisruption(FlightInstance instance) {
        if (instance.getStatus() != FlightInstanceStatus.SCHEDULED) {
            return;   // only a not-yet-departed flight can be reassigned/retimed
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
