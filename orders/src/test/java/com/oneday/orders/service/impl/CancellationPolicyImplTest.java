package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.PickupType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.CancellationPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationPolicyImplTest {

    private final CancellationPolicy policy = new CancellationPolicyImpl();

    @Test
    void daPickup_cancellableUpToPickedUp() {
        assertThat(policy.isCancellable(ShipmentState.BOOKED, PickupType.DA_PICKUP)).isTrue();
        assertThat(policy.isCancellable(ShipmentState.PICKUP_ASSIGNED, PickupType.DA_PICKUP)).isTrue();
        assertThat(policy.isCancellable(ShipmentState.PICKED_UP, PickupType.DA_PICKUP)).isTrue();
        assertThat(policy.isCancellable(ShipmentState.PICKUP_FAILED, PickupType.DA_PICKUP)).isTrue();
    }

    @Test
    void daPickup_blockedFromHandoffOnward() {
        assertThat(policy.isCancellable(ShipmentState.HANDED_TO_PICKUP_VAN, PickupType.DA_PICKUP)).isFalse();
        assertThat(policy.isCancellable(ShipmentState.AT_ORIGIN_HUB, PickupType.DA_PICKUP)).isFalse();
        assertThat(policy.isCancellable(ShipmentState.DEPARTED, PickupType.DA_PICKUP)).isFalse();
        assertThat(policy.isCancellable(ShipmentState.DROPPED, PickupType.DA_PICKUP)).isFalse();
    }

    @Test
    void selfDrop_cancellableUpToAwaitingSelfDrop() {
        assertThat(policy.isCancellable(ShipmentState.BOOKED, PickupType.SELF_DROP)).isTrue();
        assertThat(policy.isCancellable(ShipmentState.AWAITING_SELF_DROP, PickupType.SELF_DROP)).isTrue();
    }

    @Test
    void selfDrop_blockedFromOriginHubOnward() {
        assertThat(policy.isCancellable(ShipmentState.AT_ORIGIN_HUB, PickupType.SELF_DROP)).isFalse();
        // SELF_DROP has no DA pickup states, so those are not cancellable on this path either.
        assertThat(policy.isCancellable(ShipmentState.PICKED_UP, PickupType.SELF_DROP)).isFalse();
    }

    @Test
    void nullArguments_areNotCancellable() {
        assertThat(policy.isCancellable(null, PickupType.DA_PICKUP)).isFalse();
        assertThat(policy.isCancellable(ShipmentState.BOOKED, null)).isFalse();
    }
}
