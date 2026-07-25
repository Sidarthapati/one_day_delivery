package com.oneday.common.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Resolve shipments' human-readable reference numbers (e.g. {@code "1DD-BLR-20260530-00042"}) in
 * bulk, without importing the orders module. Implemented in orders (M4); consumed in dispatch (M5)
 * so the DA app can address the ref-keyed delivery-OTP endpoint. Batch (one {@code findAllById}) to
 * avoid an N+1 over a DA's task list. Same cross-module pattern as {@link ShipmentLocationPort}.
 */
public interface ShipmentRefPort {

    /** Ref number per shipment id; ids without a shipment (or ref) are simply absent from the map. */
    Map<UUID, String> refsFor(Collection<UUID> shipmentIds);
}
