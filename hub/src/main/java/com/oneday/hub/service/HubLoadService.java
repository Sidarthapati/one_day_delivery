package com.oneday.hub.service;

import com.oneday.hub.domain.HubLoadSnapshot;

import java.util.UUID;

/**
 * Hub overload back-pressure (§11, M7-D-007). Rolls a {@code hub_load_snapshot} per (hub, wave) and,
 * when stand occupancy crosses the high-water mark, raises a {@code HUB_OVERLOAD_ALERT} (→ M10 +
 * station manager; also M4's advisory booking-throttle signal). Escalate, never discard — nothing
 * here stops a parcel being sorted.
 */
public interface HubLoadService {

    /** Compute + persist the current-wave snapshot for a hub, emitting an alert if overloaded. */
    HubLoadSnapshot snapshot(UUID hubId);
}
