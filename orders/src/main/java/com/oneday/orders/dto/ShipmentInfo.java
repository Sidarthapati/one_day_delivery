package com.oneday.orders.dto;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.DropType;
import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a shipment for downstream operational modules (M7 hub, etc.) that need a
 * parcel's confirmed routing/weight facts without importing the {@code Shipment} entity. The public
 * counterpart to the internal entity — see {@link com.oneday.orders.service.ShipmentLookupService}.
 *
 * <p>{@code chargeableWeightGrams} is the billed/handling weight M7 accumulates into a flight bag.
 * {@code slaDeadline} is nullable until M10's commitment timestamp is wired.</p>
 */
public record ShipmentInfo(
        UUID shipmentId,
        String shipmentRef,
        ShipmentState state,
        int chargeableWeightGrams,
        DropType dropType,
        DeliveryType deliveryType,
        String originCity,
        String destCity,
        String destPincode,
        UUID destTileId,
        Instant slaDeadline,
        // Order back-reference (null for legacy shipments booked before the Order → N abstraction).
        // Lets downstream ops modules (M11 exceptions/RTO) group a parcel with its order siblings.
        UUID orderId,
        String orderRef,
        // R4: an unresolved mid-transit RTO intent is recorded on this parcel (cancel arrived before the
        // hub scan). M7 reads this at dock-receive to skip the outbound flight sort so the parcel is
        // turned around same-city instead of flying. True ⇔ rto_requested_at set, rto_resolved_at null,
        // no return child yet.
        boolean pendingRto) {
}
