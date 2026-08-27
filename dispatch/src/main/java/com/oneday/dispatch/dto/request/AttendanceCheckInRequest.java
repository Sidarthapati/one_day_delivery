package com.oneday.dispatch.dto.request;

/**
 * A DA "I've arrived" check-in. Both coordinates are optional — when omitted the server falls back to
 * the DA's latest GPS fix. Presence is granted only if the location is within the hub geofence.
 */
public record AttendanceCheckInRequest(Double lat, Double lon) {
}
