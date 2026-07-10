package com.oneday.hub.service.impl;

import com.oneday.common.domain.enums.DropType;
import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.DeliveryBagItem;
import com.oneday.hub.domain.DeliveryBagItemStatus;
import com.oneday.hub.domain.InboundReceipt;
import com.oneday.hub.domain.SortDirection;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.DeliveryBagItemRepository;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.ParcelNotFoundException;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Receives parcels at the dock and routes them by derived arrival mode (§6, M7-D-005 — one scan, the
 * mode is read off the M4 state, never the barcode):
 * <ul>
 *   <li>VAN / SELF_DROP → first-mile origin path. A <b>same-city</b> parcel (origin city == dest
 *       city) skips the flight entirely (§12): SAMECITY_OUTBOUND, then straight into the inbound sort.</li>
 *   <li>AIRPORT → destination path (§8): the {@code hex → territory → route} ladder for DA_DELIVERY,
 *       or the hub-collect shelf for HUB_COLLECT.</li>
 * </ul>
 */
@Service
class HubReceivingServiceImpl implements HubReceivingService {

    private final ShipmentInfoPort shipmentInfoPort;
    private final InboundReceiptRepository inboundReceiptRepository;
    private final DeliveryBagItemRepository deliveryBagItemRepository;
    private final SortService sortService;
    private final HubEventProducer eventProducer;
    private final Clock clock;

    HubReceivingServiceImpl(ShipmentInfoPort shipmentInfoPort,
                            InboundReceiptRepository inboundReceiptRepository,
                            DeliveryBagItemRepository deliveryBagItemRepository,
                            SortService sortService,
                            HubEventProducer eventProducer,
                            Clock clock) {
        this.shipmentInfoPort = shipmentInfoPort;
        this.inboundReceiptRepository = inboundReceiptRepository;
        this.deliveryBagItemRepository = deliveryBagItemRepository;
        this.sortService = sortService;
        this.eventProducer = eventProducer;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReceiveResult receive(UUID hubId, String shipmentRef) {
        ShipmentInfoPort.ParcelInfo parcel = shipmentInfoPort.lookup(shipmentRef)
                .orElseThrow(() -> new ParcelNotFoundException(shipmentRef));

        // The barcode never carries the arrival mode — we derive it from the leg the parcel just
        // finished (its M4 state). VAN vs SELF_DROP vs AIRPORT map 1:1 to mutually-exclusive states.
        ArrivalMode mode = ArrivalMode.fromState(parcel.state());
        Instant now = clock.instant();

        if (mode == ArrivalMode.AIRPORT) {
            UUID receiptId = recordReceipt(parcel, hubId, mode, SortDirection.INBOUND).getId();
            return inboundDispatch(receiptId, hubId, parcel, now);
        }

        // First-mile origin arrival (VAN / SELF_DROP).
        UUID receiptId = recordReceipt(parcel, hubId, mode, SortDirection.OUTBOUND).getId();
        if (isSameCity(parcel)) {
            // §12 — origin hub IS the dest hub; collapse the air legs and sort straight for delivery.
            eventProducer.emitSameCityOutbound(parcel.shipmentId(), hubId, hubId);
            return inboundDispatch(receiptId, hubId, parcel, now);
        }
        SortService.SortResult sort = sortService.resolveOutbound(hubId, parcel, now);
        return new ReceiveResult(receiptId, parcel.shipmentId(), parcel.shipmentRef(), true, null, sort, null);
    }

    /** Destination branch (§8.2): DA_DELIVERY → ladder + delivery bag + M6 feed; HUB_COLLECT → shelf. */
    private ReceiveResult inboundDispatch(UUID receiptId, UUID hubId, ShipmentInfoPort.ParcelInfo parcel, Instant now) {
        if (parcel.dropType() == DropType.HUB_COLLECT) {
            shelfPlace(hubId, parcel);
            return new ReceiveResult(receiptId, parcel.shipmentId(), parcel.shipmentRef(), true, null, null, null);
        }
        SortService.InboundSortResult inboundSort = sortService.resolveInbound(hubId, parcel, now);
        return new ReceiveResult(receiptId, parcel.shipmentId(), parcel.shipmentRef(), true, null, null, inboundSort);
    }

    /** Hub-collect shelf placement (§8.2): the parcel waits on a shelf for the customer (AWAITING_HUB_COLLECT). */
    private void shelfPlace(UUID hubId, ShipmentInfoPort.ParcelInfo parcel) {
        deliveryBagItemRepository.save(DeliveryBagItem.builder()
                .parcelId(parcel.shipmentId())
                .shipmentRef(parcel.shipmentRef())
                .cityId(hubId)
                .hubId(hubId)
                .destHexId(parcel.destTileId())
                .dropType(DropType.HUB_COLLECT)
                .status(DeliveryBagItemStatus.ON_SHELF)
                .build());
    }

    private InboundReceipt recordReceipt(ShipmentInfoPort.ParcelInfo parcel, UUID hubId, ArrivalMode mode, SortDirection direction) {
        return inboundReceiptRepository.save(InboundReceipt.builder()
                .parcelId(parcel.shipmentId())
                .shipmentRef(parcel.shipmentRef())
                .cityId(hubId)              // hub_id == city_id in v1
                .hubId(hubId)
                .arrivalMode(mode)
                .direction(direction)
                .reconciled(true)
                .receivedAt(clock.instant())
                .build());
    }

    private boolean isSameCity(ShipmentInfoPort.ParcelInfo parcel) {
        return parcel.originCity() != null && parcel.originCity().equalsIgnoreCase(parcel.destCity());
    }
}
