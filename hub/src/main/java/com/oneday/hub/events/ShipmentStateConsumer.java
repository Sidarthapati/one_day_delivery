package com.oneday.hub.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ShipmentEventType;
import com.oneday.common.kafka.events.BaseShipmentEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.grid.service.GridService;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.hub.service.exception.UndeterminedArrivalException;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumes M4 shipment state changes from {@code oneday.shipments.events} (queue {@code hub.shipments})
 * and treats AT_ORIGIN_HUB as the outbound-sort trigger and AT_DEST_HUB as the inbound-sort trigger
 * (§5, M7-D-005). {@link HubReceivingService#receive} derives the direction from the parcel's M4 state.
 *
 * <p>The queue #-binds the exchange, so it receives every shipment event type; we accept the base
 * {@link BaseShipmentEvent} (the {@code __TypeId__} header deserializes each to its concrete subtype,
 * mirroring M5's consumer) and act only on STATE_CHANGED hub-arrivals — all other events are ignored.</p>
 *
 * <p><b>hub_id == city_id in v1</b>: the target hub is the parcel's origin/dest city, resolved to the
 * fixed grid city UUID via {@link GridService#resolveCityId} (the M4 city string is the uppercase name,
 * e.g. "DELHI", which resolves case-insensitively to grid key "delhi").</p>
 */
@Component
public class ShipmentStateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentStateConsumer.class);

    private final ShipmentInfoPort shipmentInfoPort;
    private final GridService gridService;
    private final HubReceivingService hubReceivingService;

    public ShipmentStateConsumer(ShipmentInfoPort shipmentInfoPort, GridService gridService,
                                 HubReceivingService hubReceivingService) {
        this.shipmentInfoPort = shipmentInfoPort;
        this.gridService = gridService;
        this.hubReceivingService = hubReceivingService;
    }

    @RabbitListener(queues = HubMessagingTopology.SHIPMENTS_QUEUE)
    public void onShipmentEvent(BaseShipmentEvent event) {
        if (event.getEventType() != ShipmentEventType.STATE_CHANGED) {
            return; // CREATED / CANCELLED are not hub-sort triggers
        }
        ShipmentStateChangedEvent e = (ShipmentStateChangedEvent) event;
        ShipmentState to = e.getToState();
        if (to != ShipmentState.AT_ORIGIN_HUB && to != ShipmentState.AT_DEST_HUB) {
            return;
        }

        String ref = e.getShipmentRef();
        Optional<ShipmentInfoPort.ParcelInfo> info = shipmentInfoPort.lookup(ref);
        if (info.isEmpty()) {
            log.error("{} arrival for {} but M4 lookup found no parcel — cannot sort", to, ref);
            return;
        }

        // hubId = origin city (AT_ORIGIN_HUB) or dest city (AT_DEST_HUB), both == city UUID in v1.
        String cityCode = to == ShipmentState.AT_ORIGIN_HUB ? info.get().originCity() : info.get().destCity();
        UUID hubId;
        try {
            hubId = gridService.resolveCityId(cityCode);
        } catch (ResponseStatusException ex) {
            log.error("{} arrival for {}: city '{}' does not resolve to a grid hub — cannot sort ({})",
                    to, ref, cityCode, ex.getReason());
            return;
        }

        try {
            HubReceivingService.ReceiveResult result = hubReceivingService.receive(hubId, ref);
            log.debug("{} sort for {} at hub {}: reconciled={}", to, ref, hubId, result.reconciled());
        } catch (UndeterminedArrivalException ex) {
            // The event's toState was a dock-arrival, but by the time we look the parcel up its LIVE
            // M4 state has already advanced past the hub (a stale/redelivered event on the at-least-once
            // bus, or the compressed demo racing ahead). The sort has effectively happened — ack, don't
            // dead-letter. Mirrors HubEventsConsumer's tolerance of out-of-order transitions.
            log.debug("{} arrival for {} no longer applicable (live state moved on): {}", to, ref, ex.getMessage());
        }
    }
}
