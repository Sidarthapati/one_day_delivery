package com.oneday.dispatch.dto.request;

/**
 * Admin update to the global attendance config. {@code autoPresentEnabled} (json
 * {@code auto_present_enabled}) is required — the master switch for geofence auto-present.
 */
public record AttendanceConfigUpdateRequest(Boolean autoPresentEnabled) {
}
