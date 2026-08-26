package com.oneday.dispatch.domain;

/** How a DA's attendance was established. */
public enum DaAttendanceMethod {
    /** A GPS fix landed within the hub geofence. */
    AUTO_GEOFENCE,
    /** The DA tapped "I've arrived" and was within the hub geofence. */
    MANUAL_CHECKIN,
    /** A station manager confirmed the DA present (discarded the attendance alert). */
    MANAGER_PRESENT,
    /** A station manager marked the DA absent (which also triggered the reassignment). */
    MANAGER_ABSENT
}
