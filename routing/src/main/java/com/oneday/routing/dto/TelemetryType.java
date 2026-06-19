package com.oneday.routing.dto;

// What a single van telemetry POST carries (§14.2). One door, discriminated by type: most are
// background GPS pings; ARRIVED/DEPARTED bracket a stop; DELIVER/COLLECT are the in-loop custody
// scans. Hub bookends (VAN_LOAD/VAN_UNLOAD) come through the driver-app load/return endpoints.
public enum TelemetryType {
    GPS,              // background position ping (~10s) — position only
    ARRIVED_AT_STOP,  // van reached the meeting vertex — lateness vs plan, open the stop
    DEPARTED_STOP,    // van left the vertex — advance position
    DELIVER,          // scan a parcel out to the DA (van → DA)
    COLLECT           // scan a parcel in from the DA (DA → van)
}
