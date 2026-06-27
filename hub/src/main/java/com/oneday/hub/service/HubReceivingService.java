package com.oneday.hub.service;

import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.DiscrepancyType;

import java.util.UUID;

/**
 * The dock (§6): take a parcel into hub custody, reconcile it, and kick off sortation. PR #1 handles
 * the two first-mile origin modes (VAN, SELF_DROP); AIRPORT (destination) lands in PR #2.
 */
public interface HubReceivingService {

    /** Scan a parcel in at {@code hubId} via {@code mode}; records a receipt and runs the outbound sort. */
    ReceiveResult receive(UUID hubId, String shipmentRef, ArrivalMode mode);

    record ReceiveResult(
            UUID receiptId,
            UUID shipmentId,
            String shipmentRef,
            boolean reconciled,
            DiscrepancyType discrepancyType,
            SortService.SortResult sort) {
    }
}
