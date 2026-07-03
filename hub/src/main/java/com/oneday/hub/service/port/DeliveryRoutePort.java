package com.oneday.hub.service.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam to M6 (routing): the second rung of the destination ladder (M7-D-012) — resolve a DA
 * territory to the delivery route/loop a van runs for it, read-only from M6's published nightly
 * plan. <b>Empty when no van runs that territory/day</b> — that absence is the signal to fall back
 * to a DA-territory bag the DA hub-collects (§8.1). M6 is built, so {@link RoutingDeliveryRouteAdapter}
 * wires this for real over {@code routing.service.RoutePlanLifecycleService}; unit tests mock it.
 */
public interface DeliveryRoutePort {

    /** The van loop serving {@code territoryId} in {@code cityId} on {@code date}, if a van runs it. */
    Optional<DeliveryRoute> routeForTerritory(UUID cityId, UUID territoryId, LocalDate date);

    /**
     * @param routePlanId the M6 nightly plan the route belongs to.
     * @param vanId       the van that runs the territory (from the DA's cron schedule). In v1 a van
     *                    is the loop identity M7 keys a ROUTE bag by; M6's finer (van, loopIndex)
     *                    key is not exposed here yet.
     */
    record DeliveryRoute(UUID routePlanId, UUID vanId) {
    }
}
