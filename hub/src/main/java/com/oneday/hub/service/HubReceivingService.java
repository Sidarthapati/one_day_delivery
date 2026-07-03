package com.oneday.hub.service;

import com.oneday.hub.domain.DiscrepancyType;

import java.util.UUID;

/**
 * The dock (§6): take a parcel into hub custody, reconcile it, and kick off sortation. The arrival
 * mode is <b>derived</b> from the parcel's M4 state, not passed in (M7-D-005), and routes the parcel:
 * VAN/SELF_DROP → outbound flight sort (§7); AIRPORT → inbound delivery sort (§8); a same-city parcel
 * skips the flight path and re-enters the inbound sort directly (§12). HUB_COLLECT parcels go to the
 * hub-collect shelf instead of a delivery bag.
 */
public interface HubReceivingService {

    /** Scan a parcel in at {@code hubId}; derives the arrival mode, records a receipt, runs the right sort. */
    ReceiveResult receive(UUID hubId, String shipmentRef);

    /**
     * Exactly one of {@code sort} (OUTBOUND flight path) / {@code inboundSort} (INBOUND delivery path)
     * is set, per the resolved direction; both are null for a HUB_COLLECT parcel placed on the shelf.
     */
    record ReceiveResult(
            UUID receiptId,
            UUID shipmentId,
            String shipmentRef,
            boolean reconciled,
            DiscrepancyType discrepancyType,
            SortService.SortResult sort,
            SortService.InboundSortResult inboundSort) {
    }
}
