package com.oneday.common.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Batch-resolve the field-facing contact + address details a DA needs on the ground: who to call and
 * where to go for each end of a shipment, plus the COD amount to collect. Implemented in orders (M4);
 * consumed in dispatch (M5) so the DA app can show a "Call" button + address block + "Collect ₹X"
 * without importing the orders module. Batch (one {@code findAllById}) to avoid an N+1 over a DA's
 * task list. Same cross-module pattern as {@link ShipmentRefPort} / {@link ShipmentLocationPort}.
 */
public interface ShipmentContactPort {

    /** Contact bundle per shipment id; ids without a shipment are simply absent from the map. */
    Map<UUID, ShipmentContact> contactsFor(Collection<UUID> shipmentIds);

    /**
     * Both ends of a shipment in one record — dispatch picks the sender end for a PICKUP task and the
     * receiver end for a DELIVERY task. Address strings are pre-composed here so the boundary stays a
     * plain String (no cross-module Address import). {@code codAmountPaise} is null unless COD.
     */
    record ShipmentContact(
            String senderName,
            String senderPhone,
            String originAddress,
            String receiverName,
            String receiverPhone,
            String destAddress,
            Long codAmountPaise) {
    }
}
