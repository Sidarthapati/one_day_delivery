package com.oneday.hub.service.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam to M3 (grid): the first rung of the destination ladder (M7-D-012) — resolve a parcel's
 * destination hex to the DA territory that serves it (and the M3 zone it sits in, for the pool-
 * overflow fallback). M3 is built, so {@link GridTerritoryAdapter} wires this for real over
 * {@code grid.service.GridService}; unit tests mock the port.
 */
public interface TerritoryPort {

    /** The DA territory (and zone) covering {@code destHexId} in {@code cityId} on {@code date}. */
    Optional<DaTerritory> territoryForHex(UUID cityId, UUID destHexId, LocalDate date);

    /**
     * @param territoryId the DA whose territory covers the hex (M3 keys a territory by its DA).
     * @param zoneId      the M3 zone the territory sits in — nullable until M3 exposes zones (Q13).
     */
    record DaTerritory(UUID territoryId, UUID zoneId) {
    }
}
