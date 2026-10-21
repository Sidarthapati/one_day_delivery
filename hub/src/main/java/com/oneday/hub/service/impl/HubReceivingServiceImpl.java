package com.oneday.hub.service.impl;

import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.DeliveryBagItem;
import com.oneday.hub.domain.DeliveryBagItemStatus;
import com.oneday.hub.domain.InboundReceipt;
import com.oneday.hub.domain.SortDirection;
import com.oneday.hub.events.HubArrivalScanProducer;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.DeliveryBagItemRepository;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.ParcelNotFoundException;
import com.oneday.hub.service.port.ShipmentInfoPort;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.TransitionContext;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.log.AuditLog;
import com.oneday.common.log.LogContext;
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

    private static final String RTO_SOURCE = "m7-hub-receive";

    private final ShipmentInfoPort shipmentInfoPort;
    private final InboundReceiptRepository inboundReceiptRepository;
    private final DeliveryBagItemRepository deliveryBagItemRepository;
    private final SortService sortService;
    private final HubEventProducer eventProducer;
    private final HubArrivalScanProducer arrivalScanProducer;
    private final ReturnService returnService;
    private final Clock clock;

    HubReceivingServiceImpl(ShipmentInfoPort shipmentInfoPort,
                            InboundReceiptRepository inboundReceiptRepository,
                            DeliveryBagItemRepository deliveryBagItemRepository,
                            SortService sortService,
                            HubEventProducer eventProducer,
                            HubArrivalScanProducer arrivalScanProducer,
                            ReturnService returnService,
                            Clock clock) {
        this.shipmentInfoPort = shipmentInfoPort;
        this.inboundReceiptRepository = inboundReceiptRepository;
        this.deliveryBagItemRepository = deliveryBagItemRepository;
        this.sortService = sortService;
        this.eventProducer = eventProducer;
        this.arrivalScanProducer = arrivalScanProducer;
        this.returnService = returnService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReceiveResult receive(UUID hubId, String shipmentRef) {
        ShipmentInfoPort.ParcelInfo parcel = shipmentInfoPort.lookup(shipmentRef)
                .orElseThrow(() -> new ParcelNotFoundException(shipmentRef));

        try (var ignored = LogContext.forShipment(parcel.shipmentId(), parcel.shipmentRef(), parcel.shipmentId())) {
            // The barcode never carries the arrival mode — we derive it from the leg the parcel just
            // finished (its M4 state). A not-yet-arrived parcel (e.g. still PICKED_UP — the DA hasn't
            // tapped handoff, so it's not RETURNED_TO_HUB yet) throws UndeterminedArrivalException → 409.
            ArrivalMode mode = ArrivalMode.fromState(parcel.state());
            Instant now = clock.instant();
            boolean airport = mode == ArrivalMode.AIRPORT;
            SortDirection direction = airport ? SortDirection.INBOUND : SortDirection.OUTBOUND;
            UUID receiptId = recordReceipt(parcel, hubId, mode, direction).getId();

            // One scan does everything. If the parcel carries an unresolved RTO intent (the cancel arrived
            // BEFORE this dock scan), resolve the return and sort the CHILD into its bag in THIS scan — a
            // same-city delivery bag at the origin hub, a reverse-lane flight bag at the dest hub. Must run
            // before any forward dispatch so a same-city original isn't delivered onward. M4 drives arrival
            // + mint synchronously here, so we do NOT also emit the async arrival scan (it would later try to
            // re-transition out of RTO_INITIATED → DLQ).
            if (parcel.pendingRto()) {
                return resolveRtoAndSort(receiptId, hubId, parcel, airport, now);
            }

            if (airport) {
                // The dock scan IS the record of dest-hub arrival (→ AT_DEST_HUB) — not a console button.
                arrivalScanProducer.emitArrival(parcel.shipmentId(), parcel.shipmentRef(), hubId,
                        ScanEventType.HUB_DEST_IN, "INBOUND");
                return inboundDispatch(receiptId, hubId, parcel, now);
            }

            // First-mile origin arrival (VAN_MEETING / HUB_RETURN / SELF_DROP).
            // The dock scan IS the record of origin-hub arrival (→ AT_ORIGIN_HUB) — not the DA app button.
            ScanEventType originScan = mode == ArrivalMode.SELF_DROP
                    ? ScanEventType.SELF_DROP_ACCEPTED : ScanEventType.HUB_ORIGIN_IN;
            arrivalScanProducer.emitArrival(parcel.shipmentId(), parcel.shipmentRef(), hubId, originScan, "OUTBOUND");
            if (isSameCity(parcel)) {
                // §12 — origin hub IS the dest hub; collapse the air legs and sort straight for delivery.
                eventProducer.emitSameCityOutbound(parcel.shipmentId(), hubId, hubId);
                return inboundDispatch(receiptId, hubId, parcel, now);
            }
            SortService.SortResult sort = sortService.resolveOutbound(hubId, parcel, now);
            return new ReceiveResult(receiptId, parcel.shipmentId(), parcel.shipmentRef(), true, null, sort, null);
        }
    }

    /**
     * R4 one-scan RTO: the cancel arrived before this dock scan, so the parcel is turned around HERE in a
     * single operator scan. M4 drives the hub arrival and mints the return child ({@code <ref>_R})
     * synchronously; we then sort that child the same way a normal dock arrival would — a same-city child
     * into a delivery bag (resolveInbound), a reverse child into a reverse-lane flight bag (resolveOutbound).
     * No async arrival scan is emitted on this path (arrival was driven synchronously inside M4).
     */
    private ReceiveResult resolveRtoAndSort(UUID receiptId, UUID hubId, ShipmentInfoPort.ParcelInfo parcel,
                                            boolean airport, Instant now) {
        ShipmentState arrivalHubState = airport ? ShipmentState.AT_DEST_HUB : ShipmentState.AT_ORIGIN_HUB;
        ReturnService.ReturnResult rto = returnService.resolveOnHubArrival(
                        parcel.shipmentId(), arrivalHubState, TransitionContext.fromSystem(RTO_SOURCE))
                .orElseThrow(() -> new IllegalStateException(
                        "pendingRto parcel " + parcel.shipmentRef() + " resolved to no return child"));

        AuditLog.event("hub.rto_resolved_on_scan")
                .kv("shipmentRef", parcel.shipmentRef())
                .kv("parcelId", parcel.shipmentId())
                .kv("hubId", hubId)
                .kv("childRef", rto.childShipmentRef())
                .log();

        // Sort the freshly-minted child by its own geography: same-city → delivery bag, reverse → flight bag.
        ShipmentInfoPort.ParcelInfo child = shipmentInfoPort.lookup(rto.childShipmentRef())
                .orElseThrow(() -> new ParcelNotFoundException(rto.childShipmentRef()));
        if (isSameCity(child)) {
            return inboundDispatch(receiptId, hubId, child, now);
        }
        SortService.SortResult sort = sortService.resolveOutbound(hubId, child, now);
        return new ReceiveResult(receiptId, child.shipmentId(), child.shipmentRef(), true, null, sort, null);
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
        InboundReceipt receipt = inboundReceiptRepository.save(InboundReceipt.builder()
                .parcelId(parcel.shipmentId())
                .shipmentRef(parcel.shipmentRef())
                .cityId(hubId)              // hub_id == city_id in v1
                .hubId(hubId)
                .arrivalMode(mode)
                .direction(direction)
                .reconciled(true)
                .receivedAt(clock.instant())
                .build());

        AuditLog.event("hub.parcel_received")
                .kv("shipmentRef", parcel.shipmentRef())
                .kv("parcelId", parcel.shipmentId())
                .kv("hubId", hubId)
                .kv("arrivalMode", mode)
                .kv("direction", direction)
                .log();
        return receipt;
    }

    private boolean isSameCity(ShipmentInfoPort.ParcelInfo parcel) {
        return parcel.originCity() != null && parcel.originCity().equalsIgnoreCase(parcel.destCity());
    }
}
