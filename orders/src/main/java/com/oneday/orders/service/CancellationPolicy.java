package com.oneday.orders.service;

import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;

/**
 * Decides whether a shipment may still be cancelled given its current state and pickup path.
 *
 * <p><b>[BD-SEAM]</b> Cancellation cutoffs are a business rule that will be revised (e.g. as
 * refund policy or partner SLAs change). Keeping the decision behind this single interface means
 * a cutoff revision touches only the {@link CancellationPolicy} bean — never the service,
 * controller, or state machine. See {@code docs/M4/M4-IMPL-PLAN.md} PR #13 and
 * {@code M4-ORDERS-DESIGN.md} BD-001.</p>
 */
public interface CancellationPolicy {

    /**
     * @param state      the shipment's current state
     * @param pickupType DA_PICKUP or SELF_DROP — selects which cutoff applies
     * @return {@code true} if a customer-initiated cancellation is still permitted
     */
    boolean isCancellable(ShipmentState state, PickupType pickupType);
}
