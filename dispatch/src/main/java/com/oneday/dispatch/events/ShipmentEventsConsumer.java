package com.oneday.dispatch.events;

import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.kafka.events.BaseShipmentEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
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
            // STATE_CHANGED → delivery assignment; CANCELLED → cancelTask — wired in PR #9.
            case STATE_CHANGED, CANCELLED ->
                    log.debug("Shipment {} {} not handled yet (PR #9)", event.getShipmentId(), event.getEventType());
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
}
