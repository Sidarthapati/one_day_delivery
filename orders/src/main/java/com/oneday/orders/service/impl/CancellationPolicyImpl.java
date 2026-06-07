package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.CancellationPolicy;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * V1 cancellation cutoffs (BD-001):
 * <ul>
 *   <li><b>DA_PICKUP</b> — cancellable up to and including {@code PICKED_UP}; blocked once the
 *       parcel is handed to the pickup van ({@code HANDED_TO_PICKUP_VAN}) and beyond.</li>
 *   <li><b>SELF_DROP</b> — cancellable up to and including {@code AWAITING_SELF_DROP}; blocked
 *       once it reaches the origin hub ({@code AT_ORIGIN_HUB}) and beyond.</li>
 * </ul>
 *
 * <p>Both paths share {@code BOOKED}. The {@code PICKUP_FAILED} state is also cancellable (the
 * customer may abandon after a failed pickup attempt) — the state machine already registers that
 * edge, so we honour it here for the DA_PICKUP path.</p>
 */
@Component
class CancellationPolicyImpl implements CancellationPolicy {

    private static final Set<ShipmentState> DA_PICKUP_CANCELLABLE = EnumSet.of(
            ShipmentState.BOOKED,
            ShipmentState.PICKUP_ASSIGNED,
            ShipmentState.PICKED_UP,
            ShipmentState.PICKUP_FAILED);

    private static final Set<ShipmentState> SELF_DROP_CANCELLABLE = EnumSet.of(
            ShipmentState.BOOKED,
            ShipmentState.AWAITING_SELF_DROP);

    @Override
    public boolean isCancellable(ShipmentState state, PickupType pickupType) {
        if (state == null || pickupType == null) {
            return false;
        }
        return switch (pickupType) {
            case DA_PICKUP -> DA_PICKUP_CANCELLABLE.contains(state);
            case SELF_DROP -> SELF_DROP_CANCELLABLE.contains(state);
        };
    }
}
