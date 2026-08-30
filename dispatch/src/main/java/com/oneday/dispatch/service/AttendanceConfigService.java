package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.AttendanceConfig;

import java.util.UUID;

/**
 * Reads and writes the global attendance config (a single row). {@link #isAutoPresentEnabled()} is on
 * the hot GPS-ping path, so it is served from an in-memory cache refreshed on write.
 */
public interface AttendanceConfigService {

    /** Whether geofence auto-present is currently enabled (cached; cheap to call per ping). */
    boolean isAutoPresentEnabled();

    /** The current config row (for the admin read). */
    AttendanceConfig get();

    /** Flip the auto-present switch; returns the persisted row and refreshes the cache. */
    AttendanceConfig setAutoPresentEnabled(boolean enabled, UUID actorUserId);
}
