package com.oneday.orders.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A parcel's full ops picture ({@code GET /api/v1/admin/shipments/{ref}/timeline}): the header (identity +
 * the already-stored data-model fields ops asks for) plus one merged, time-ordered event stream weaving
 * M4 state transitions and M8 scans together — the "search → timeline" drill-down.
 */
public record ShipmentTimelineResponse(
        String shipmentRef,
        UUID shipmentId,
        ShipmentState state,
        CustomerType customerType,
        DeliveryType deliveryType,
        String originCity,
        String destCity,
        String senderName,
        String receiverName,
        Integer chargeableWeightGrams,
        Instant etaPromised,
        Instant lastScanAt,
        Instant createdAt,
        List<TimelineEvent> events) {

    /**
     * @param kind   {@code STATE} (a shipment state transition) or {@code SCAN} (a ledger scan)
     * @param label  the headline — the new state, or the scan type
     * @param detail supporting context — actor / trigger / node, or null
     */
    public record TimelineEvent(Instant at, String kind, String label, String detail) {
    }
}
