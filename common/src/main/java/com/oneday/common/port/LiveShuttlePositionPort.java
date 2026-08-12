package com.oneday.common.port;

import java.util.Optional;
import java.util.UUID;

/**
 * The live GPS of the shuttle agent currently carrying a parcel on a hub↔airport leg (origin
 * hub→airport, or dest airport→hub). Implemented in M12 (shuttle) over {@code shuttle_leg →
 * shuttle_live_status}; consumed by the M4 tracking read path. Same cross-module seam as
 * {@link LiveVanPositionPort} / {@link LiveDaPositionPort} — both sides depend only on {@code common},
 * so M4 never imports shuttle internals.
 */
public interface LiveShuttlePositionPort {

    /** The carrying shuttle agent's latest GPS, or empty if the parcel isn't on a shuttle leg / no fix yet. */
    Optional<LivePosition> forShipment(UUID shipmentId);
}
