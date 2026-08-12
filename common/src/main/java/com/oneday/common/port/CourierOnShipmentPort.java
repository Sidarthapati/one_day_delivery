package com.oneday.common.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The DA currently on a shipment's first-mile pickup or last-mile delivery — who to call and which leg
 * they're on. Implemented in M5 (dispatch) over the active {@code dispatch_queue} task + the auth DA
 * directory; consumed by the M4 tracking read path so the customer sees the assigned DA exactly while a
 * live DA is on the parcel (same signal as {@link LiveDaPositionPort}). The reciprocal of
 * {@link ShipmentContactPort}, which hands the DA the customer's contact. Both sides depend only on
 * {@code common}, so M4 never imports dispatch internals.
 */
public interface CourierOnShipmentPort {

    /** The DA on this shipment's active pickup/delivery task, or empty if none is currently assigned. */
    Optional<Courier> forShipment(UUID shipmentId);

    record Courier(String name, String phone, Role role) {}

    enum Role { PICKUP, DELIVERY }
}
