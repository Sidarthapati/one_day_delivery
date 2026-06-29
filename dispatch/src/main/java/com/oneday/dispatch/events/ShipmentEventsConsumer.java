package com.oneday.dispatch.events;

import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.BaseShipmentEvent;
import com.oneday.common.kafka.events.ShipmentCancelledEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Single ordered consumer of M4's shipment lifecycle ({@code oneday.shipments.events}, queue
 * {@code m5.shipments}). One queue keeps a shipment's CREATED → … events in order; this class
 * dispatches each by type. PR #8 wires CREATED (pickup assignment); STATE_CHANGED and CANCELLED are
 * acknowledged and ignored until PR #9 fills them in.
 *
 * <p>The shared Jackson converter deserializes each message to its concrete payload via the
 * {@code __TypeId__} header, so the {@link BaseShipmentEvent} parameter receives the right subtype.</p>
 */
@Component
public class ShipmentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventsConsumer.class);

    /** States in the delivery (last-mile) phase — a cancel here targets the DELIVERY task. */
    private static final Set<ShipmentState> DELIVERY_PHASE = EnumSet.of(
            ShipmentState.HANDED_TO_DROP_VAN, ShipmentState.DROP_ASSIGNED,
            ShipmentState.DROP_COLLECTED, ShipmentState.DROPPED,
            ShipmentState.AWAITING_HUB_COLLECT, ShipmentState.HUB_COLLECTED,
            ShipmentState.DELIVERY_FAILED);

    private final DispatchService dispatchService;
    private final DispatchQueueRepository queueRepository;
    private final GridService gridService;

    public ShipmentEventsConsumer(DispatchService dispatchService,
                                  DispatchQueueRepository queueRepository,
                                  GridService gridService) {
        this.dispatchService = dispatchService;
        this.queueRepository = queueRepository;
        this.gridService = gridService;
    }

    @RabbitListener(queues = DispatchMessagingTopology.SHIPMENTS_QUEUE)
    public void onShipmentEvent(BaseShipmentEvent event) {
        if (event.getEventType() == null) {
            log.warn("Shipment event {} has no eventType — ignoring", event.getShipmentId());
            return;
        }
        switch (event.getEventType()) {
            case CREATED -> handleCreated((ShipmentCreatedEvent) event);
            case STATE_CHANGED -> handleStateChanged((ShipmentStateChangedEvent) event);
            case CANCELLED -> handleCancelled((ShipmentCancelledEvent) event);
        }
    }

    /**
     * Kick off first-mile pickup assignment.
     *
     * <ul>
     *   <li>{@code SELF_DROP} needs no DA — ignored (sender brings it to the hub).</li>
     *   <li>Idempotent: a shipment with an already-active PICKUP task is skipped (redelivery). A prior
     *       FAILED task does not block re-assignment (partial unique index permits it).</li>
     *   <li>{@code cityId} (absent from the event) and the origin tile are resolved from the pickup
     *       coordinates via M3; a null {@code originTileId} is logged as a data gap, then back-filled.</li>
     * </ul>
     *
     * <p>Unprocessable messages (missing coordinates, point outside every grid) are logged at ERROR and
     * acked — a retry cannot fix them. An unexpected failure (e.g. DB outage) propagates so the
     * container retries and finally dead-letters per {@code spring.rabbitmq.listener.simple.retry}.</p>
     */
    private void handleCreated(ShipmentCreatedEvent event) {
        UUID shipmentId = event.getShipmentId();

        if (event.getPickupType() == PickupType.SELF_DROP) {
            log.debug("Shipment {} is SELF_DROP — no pickup assignment", shipmentId);
            return;
        }

        if (queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, TaskType.PICKUP).isPresent()) {
            log.debug("Shipment {} already has an active PICKUP task — skipping", shipmentId);
            return;
        }

        Double lat = event.getOriginLat();
        Double lon = event.getOriginLon();
        if (lat == null || lon == null) {
            log.error("Shipment {} CREATED without pickup coordinates — cannot assign pickup", shipmentId);
            return;
        }

        ServiceableAtResponse loc = gridService.serviceableAt(lat, lon);
        if (loc == null || loc.cityId() == null) {
            log.error("Shipment {} pickup point ({},{}) is outside every serviceable grid — cannot assign",
                    shipmentId, lat, lon);
            return;
        }

        UUID tileId = event.getOriginTileId();
        if (tileId == null) {
            tileId = loc.hexId();
            log.error("Shipment {} CREATED without originTileId — resolved {} from coordinates (M4/M3 data gap)",
                    shipmentId, tileId);
        }

        String paymentMode = event.getPaymentMode() != null ? event.getPaymentMode().name() : null;
        AssignmentResult result = dispatchService.assignPickup(
                shipmentId, loc.cityId(), lat, lon, tileId, paymentMode);
        log.debug("Pickup assignment for shipment {}: {}", shipmentId, result.outcome());
    }

    /**
     * Last-mile delivery assignment is triggered when a shipment reaches {@code HANDED_TO_DROP_VAN}
     * with {@code DA_DELIVERY}. <b>Blocked on the M4 contract (Q-M4-2):</b> {@code ShipmentStateChangedEvent}
     * carries no destination coordinates / tile / dropType, so M5 cannot place the task yet. We log the
     * gap when the trigger state arrives; wiring {@code dispatchService.assignDelivery} awaits either an
     * enriched event or an M4 shipment-query port.
     */
    private void handleStateChanged(ShipmentStateChangedEvent event) {
        if (event.getToState() == ShipmentState.HANDED_TO_DROP_VAN) {
            log.error("Shipment {} reached HANDED_TO_DROP_VAN but delivery assignment is blocked — "
                    + "ShipmentStateChangedEvent lacks dest coords/tile/dropType (Q-M4-2)", event.getShipmentId());
        }
    }

    /**
     * A cancelled shipment drops its active dispatch task. The task type is inferred from the state at
     * cancellation; {@link DispatchService#cancelTask} is a no-op if nothing is active and removes +
     * resequences a QUEUED task. An IN_PROGRESS task cannot be cancelled (the DA is mid-task) — logged
     * for ops and acked (M11 resolves it).
     */
    private void handleCancelled(ShipmentCancelledEvent event) {
        TaskType taskType = DELIVERY_PHASE.contains(event.getCancelledAtState())
                ? TaskType.DELIVERY : TaskType.PICKUP;
        // cancelTask removes the task from the DA's active load whether it was QUEUED or IN_PROGRESS
        // (an in-progress cancellation becomes an RTO — see DispatchServiceImpl#cancelTask).
        dispatchService.cancelTask(event.getShipmentId(), taskType);
    }
}
