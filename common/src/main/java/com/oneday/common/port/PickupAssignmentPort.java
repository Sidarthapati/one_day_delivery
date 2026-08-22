package com.oneday.common.port;

import java.util.UUID;

/**
 * Answers "is this DA the one M5 actually assigned to pick up this shipment?" — implemented in
 * dispatch (which owns the assignment), consumed by orders to authorize first-mile measurement so a
 * delivery associate can only measure a parcel they were assigned. Both depend only on {@code common}.
 */
public interface PickupAssignmentPort {

    /** True if {@code daId} holds the active (QUEUED/IN_PROGRESS) pickup task for {@code shipmentId}. */
    boolean isActivePickupDa(UUID daId, UUID shipmentId);
}
