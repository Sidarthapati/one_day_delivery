package com.oneday.common.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The current pickup load of the DA already assigned to a shipment's first-mile pickup — used to
 * weight-check before letting a merchant add another parcel to an in-progress pickup. Implemented in
 * M5 (dispatch) over the active {@code dispatch_queue} tasks (the same lookup as
 * {@link CourierOnShipmentPort}); consumed by M4 (orders) which owns parcel weights and compares the
 * summed load against the DA's vehicle capacity. Both sides depend only on {@code common}, so orders
 * never imports dispatch internals.
 */
public interface DaPickupLoadPort {

    /**
     * The DA on this shipment's active pickup task plus every shipment currently on that DA's active
     * (QUEUED/IN_PROGRESS) pickup queue for the day, or empty when no DA is assigned to the pickup yet
     * (in which case there is nothing to weigh against — dispatch will place the new parcel normally).
     */
    Optional<AssignedPickupLoad> assignedPickupLoad(UUID shipmentId);

    /** A DA and the shipment ids on their active pickup queue (includes the queried shipment's siblings). */
    record AssignedPickupLoad(UUID daId, List<UUID> pickupShipmentIds) {}
}
