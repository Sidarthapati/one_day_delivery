package com.oneday.hub.service.impl;

import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.InboundReceipt;
import com.oneday.hub.domain.SortDirection;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.service.HubReceivingService;
import com.oneday.hub.service.SortService;
import com.oneday.hub.service.exception.ParcelNotFoundException;
import com.oneday.hub.service.exception.UnsupportedArrivalModeException;
import com.oneday.hub.service.port.ShipmentInfoPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Receives first-mile parcels at the dock (§6). VAN arrivals begin sorting off the AT_ORIGIN_HUB
 * state already driven by M6's VAN_UNLOAD scan (M7-D-005 — one scan, no double in-scan); SELF_DROP
 * is the non-van origin path. Per-batch reconciliation against M6's van manifest lands with the
 * full M6 seam; a single-parcel receive is reconciled by construction here.
 */
@Service
class HubReceivingServiceImpl implements HubReceivingService {

    private final ShipmentInfoPort shipmentInfoPort;
    private final InboundReceiptRepository inboundReceiptRepository;
    private final SortService sortService;
    private final Clock clock;

    HubReceivingServiceImpl(ShipmentInfoPort shipmentInfoPort,
                            InboundReceiptRepository inboundReceiptRepository,
                            SortService sortService,
                            Clock clock) {
        this.shipmentInfoPort = shipmentInfoPort;
        this.inboundReceiptRepository = inboundReceiptRepository;
        this.sortService = sortService;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReceiveResult receive(UUID hubId, String shipmentRef, ArrivalMode mode) {
        if (mode == ArrivalMode.AIRPORT) {
            throw new UnsupportedArrivalModeException(mode);   // destination hub is PR #2
        }

        ShipmentInfoPort.ParcelInfo parcel = shipmentInfoPort.lookup(shipmentRef)
                .orElseThrow(() -> new ParcelNotFoundException(shipmentRef));

        Instant now = clock.instant();
        InboundReceipt receipt = inboundReceiptRepository.save(InboundReceipt.builder()
                .parcelId(parcel.shipmentId())
                .shipmentRef(parcel.shipmentRef())
                .cityId(hubId)              // hub_id == city_id in v1
                .hubId(hubId)
                .arrivalMode(mode)
                .direction(SortDirection.OUTBOUND)
                .reconciled(true)
                .receivedAt(now)
                .build());

        SortService.SortResult sort = sortService.resolveOutbound(hubId, parcel, now);

        return new ReceiveResult(receipt.getId(), parcel.shipmentId(), parcel.shipmentRef(),
                true, null, sort);
    }
}
