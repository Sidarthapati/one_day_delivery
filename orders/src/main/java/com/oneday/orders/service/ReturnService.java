package com.oneday.orders.service;

import com.oneday.common.domain.enums.ReturnReason;

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
     * Spawn a return child for {@code originalShipmentId}. Idempotent per original — a second call
     * returns the existing child rather than minting another.
     *
     * @param originalShipmentId the shipment being returned to its sender
     * @param reason             why (drives nothing structural in v1; recorded for ops/audit)
     * @param ctx                transition metadata (who/what triggered it)
     * @return the minted (or pre-existing) return child's identity
     */
    ReturnResult initiateReturn(UUID originalShipmentId, ReturnReason reason, TransitionContext ctx);

    /** The return child spawned for an original shipment. */
    record ReturnResult(UUID childShipmentId, String childShipmentRef, UUID originalShipmentId) {}
}
