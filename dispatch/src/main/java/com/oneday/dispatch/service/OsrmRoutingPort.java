package com.oneday.dispatch.service;

import com.oneday.dispatch.service.model.LatLon;

import java.util.List;
import java.util.OptionalLong;

/**
 * Point-to-point / multi-waypoint road-network travel time, used only to confirm <em>borderline</em>
 * cron-feasibility decisions (design §9.2). The fast path is always a haversine estimate; OSRM is
 * consulted just when the fast estimate lands within the confirm threshold of the cutoff.
 *
 * <p>M3/grid exposes no public routing port yet (only an internal matrix client), so M5 owns this
 * abstraction. The real implementation — an HTTP call to OSRM {@code /route} guarded by a circuit
 * breaker — lands in PR #14; until then a placeholder bean returns {@link OptionalLong#empty()} and
 * the feasibility engine falls back to a conservative haversine.</p>
 */
public interface OsrmRoutingPort {

    /**
     * Total driving duration in seconds to traverse {@code waypoints} in order, or
     * {@link OptionalLong#empty()} when OSRM is unavailable (e.g. the circuit breaker is open) — the
     * caller then uses its haversine fallback rather than blocking the assignment.
     */
    OptionalLong routeDurationSeconds(List<LatLon> waypoints);
}
