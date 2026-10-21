package com.oneday.orders.service;

import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;

import java.util.Optional;
import java.util.UUID;

/**
 * The single, extensible entry point for returns (RTO). A return is NOT a subsystem — it is the same
 * parcel run through the same pipeline backwards, modelled as a NEW child shipment ({@code <ref>_R})
 * under the same {@code ParcelOrder} with reversed geography re-resolved, born at the hub the parcel
 * already sits in. The child then flows the existing pipeline (hub sort → flight → hub → deliver to
 * the sender) with no new movement code; the original is left with two markers — RTO_INITIATED (a
 * return was spawned) and, when the child is delivered, RTO_COMPLETED.
 */
public interface ReturnService {

    /**
     * Which physical lane the return child travels, decided by where the parcel is when the return
     * fires. Both births are at a hub and reuse the same {@code mintChild} + normal pipeline.
     */
    enum ReturnLane {
        /**
         * The parcel has reached (or will reach) the destination hub — the classic return: reverse the
         * geography (return origin = original dest) and fly it back to the sender. This is the
         * delivery-attempt RTO and every committed mid-transit case.
         */
        REVERSE_FROM_DEST,
        /**
         * The parcel never left the origin city (pre-flight recall) — deliver it straight back to the
         * sender within the origin city (no flight): return origin = original origin, SAME_CITY.
         */
        SAME_CITY_FROM_ORIGIN
    }

    /**
     * Spawn a {@link ReturnLane#REVERSE_FROM_DEST} return child (the delivery-attempt / dest-hub case).
     * Equivalent to {@link #initiateReturn(UUID, ReturnReason, ReturnLane, TransitionContext)} with
     * {@code REVERSE_FROM_DEST}.
     */
    ReturnResult initiateReturn(UUID originalShipmentId, ReturnReason reason, TransitionContext ctx);

    /**
     * Spawn a return child for {@code originalShipmentId} on the given {@code lane}. Idempotent per
     * original — a second call returns the existing child rather than minting another.
     *
     * @param originalShipmentId the shipment being returned to its sender
     * @param reason             why (recorded for ops/audit)
     * @param lane               reverse-lane (fly back from the dest hub) vs same-city (from the origin hub)
     * @param ctx                transition metadata (who/what triggered it)
     * @return the minted (or pre-existing) return child's identity
     */
    ReturnResult initiateReturn(UUID originalShipmentId, ReturnReason reason, ReturnLane lane,
                                TransitionContext ctx);

    /**
     * Resolve a pending mid-transit RTO at the dock in the SAME scan that receives the parcel (one
     * physical scan does everything). If {@code shipmentId} carries an unresolved RTO intent, this
     * drives its hub arrival ({@code arrivalHubState}) and mints the return child synchronously, so the
     * caller (M7) can immediately sort that child into the right bag — a same-city delivery bag (origin
     * hub) or a reverse-lane flight bag (dest hub). No pending intent → {@link Optional#empty()} and the
     * caller proceeds with the normal sort. Idempotent: the AFTER_COMMIT {@code RtoIntentResolver} and a
     * repeat call both no-op on an already-resolved intent.
     *
     * @param shipmentId     the shipment being dock-received
     * @param arrivalHubState the hub state this scan records — {@code AT_ORIGIN_HUB} or {@code AT_DEST_HUB}
     * @param ctx            transition metadata (who/what triggered it)
     * @return the minted (or pre-existing) return child, or empty if no RTO was pending
     */
    Optional<ReturnResult> resolveOnHubArrival(UUID shipmentId, ShipmentState arrivalHubState,
                                               TransitionContext ctx);

    /** The return child spawned for an original shipment. */
    record ReturnResult(UUID childShipmentId, String childShipmentRef, UUID originalShipmentId) {}
}
