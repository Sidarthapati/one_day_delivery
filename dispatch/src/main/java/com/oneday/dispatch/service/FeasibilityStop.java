package com.oneday.dispatch.service;

import com.oneday.dispatch.service.model.LatLon;

/**
 * One stop the DA must service: its location plus the on-site service time (seconds). The service
 * time is resolved per tile from M3's {@code tile_demand_snapshot} (default 12 min if bootstrapped)
 * and cached at shift start — PR #7 supplies it when building a {@link FeasibilityRequest}.
 */
public record FeasibilityStop(LatLon location, long serviceSeconds) {

    public FeasibilityStop {
        if (location == null) {
            throw new IllegalArgumentException("stop location is required");
        }
        if (serviceSeconds < 0) {
            throw new IllegalArgumentException("serviceSeconds must be >= 0");
        }
    }
}
