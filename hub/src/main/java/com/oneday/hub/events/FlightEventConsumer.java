package com.oneday.hub.events;

import com.oneday.common.kafka.events.flight.FlightReassignedEvent;
import com.oneday.common.kafka.events.flight.FlightTimeChangedEvent;
import com.oneday.hub.service.BagReassignmentService;
import com.oneday.hub.service.FlightBagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M9's flight-decision events from {@code oneday.flight.events} (§9, §10). M7 executes only —
 * it never decides a flight (M7-D-006):
 * <ul>
 *   <li>{@link FlightReassignedEvent} → re-point the bag(s) via {@link BagReassignmentService} — the
 *       sole reschedule trigger; covers optimisation / cancellation / capacity / prepone-overflow.</li>
 *   <li>{@link FlightTimeChangedEvent} → advisory seal-window shift on the same flight; no parcels move.</li>
 * </ul>
 * Other flight events (DEPARTED/LANDED/…) need no reaction here — landing is the physical HUB_DEST_IN
 * dock scan (PR #2), not a flight event.
 *
 * <p>Enabled now that M9's reassignment engine produces these events. Also still directly reachable
 * in tests and via the {@code /hub/{hubId}/bags/reassign-flight} endpoint.</p>
 */
@Component
@RabbitListener(queues = HubMessagingTopology.FLIGHT_QUEUE, autoStartup = "true")
public class FlightEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FlightEventConsumer.class);

    private final BagReassignmentService reassignmentService;
    private final FlightBagService flightBagService;

    public FlightEventConsumer(BagReassignmentService reassignmentService, FlightBagService flightBagService) {
        this.reassignmentService = reassignmentService;
        this.flightBagService = flightBagService;
    }

    @RabbitHandler
    public void onReassigned(FlightReassignedEvent event) {
        reassignmentService.reassign(new BagReassignmentService.FlightReassignmentCommand(
                event.toFlightNo(), event.toFlightDate(), event.destHub(), event.newCutoff(),
                event.fromFlightNo(), event.parcelIds(), event.reason()));
        log.debug("Executed FLIGHT_REASSIGNED to {} ({})", event.toFlightNo(), event.reason());
    }

    @RabbitHandler
    public void onTimeChanged(FlightTimeChangedEvent event) {
        flightBagService.updateBagCutoff(event.flightNo(), event.flightDate(), event.destHub(), event.newCutoff());
        log.debug("Applied FLIGHT_TIME_CHANGED cutoff for {}", event.flightNo());
    }

    @RabbitHandler(isDefault = true)
    public void onOther(Object event) {
        // DEPARTED/LANDED/RTO_IN_TRANSIT etc. — no hub reaction (M7-D-006).
        log.trace("Ignoring non-reschedule flight event {}", event.getClass().getSimpleName());
    }
}
