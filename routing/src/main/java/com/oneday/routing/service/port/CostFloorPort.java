package com.oneday.routing.service.port;

import java.util.UUID;

/**
 * Seam to M2 (pricing). The optimiser weights arcs by cost, not just travel time (C14, M6-D-010).
 * Until M2 ships, {@link NoOpCostFloorPort} returns a flat per-km van cost from config.
 *
 * <p>Mirrors M3's {@code DaRosterPort} seam: a real {@code @Component} impl (annotated so it wins
 * over the no-op) drops in when M2 lands, without touching the solver.</p>
 */
public interface CostFloorPort {

    /** Cost (INR) per km of van travel in the given city. */
    double perKmVanCostInr(UUID cityId);
}
