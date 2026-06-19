package com.oneday.routing.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/van/{vanId}/telemetry} (§14.1). The van phone POSTs this every ~10s.
 * {@code type} discriminates: GPS pings carry only lat/lon/time; ARRIVED/DEPARTED carry loopIndex +
 * stopSeq; DELIVER/COLLECT additionally carry parcelId + daId. driverId rides every scan (Q14).
 * Raw pings stay in-process — only meaningful changes reach Kafka (M6-D-012).
 */
public record VanTelemetryRequest(
        TelemetryType type,
        Double lat,
        Double lon,
        Instant time,
        UUID cityId,
        Integer loopIndex,
        Integer stopSeq,
        UUID hexVertexId,
        UUID parcelId,
        UUID daId,
        UUID driverId) {

    public Instant timeOrNow(Instant now) {
        return time != null ? time : now;
    }
}
