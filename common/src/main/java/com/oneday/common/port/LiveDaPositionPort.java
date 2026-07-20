package com.oneday.common.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The live GPS of the DA currently carrying a shipment (first-mile pickup or last-mile delivery).
 * Implemented in M5 (dispatch) over {@code da_status}; consumed by the M4 tracking read path. Same
 * cross-module pattern as {@link ShipmentLocationPort} — both sides depend only on {@code common},
 * so M4 never imports dispatch internals.
 */
public interface LiveDaPositionPort {

    /** The carrying DA's latest GPS, or empty if no DA is currently on this shipment / no fix yet. */
    Optional<LivePosition> forShipment(UUID shipmentId);
}
