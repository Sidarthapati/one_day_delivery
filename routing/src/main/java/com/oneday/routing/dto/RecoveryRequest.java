package com.oneday.routing.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Body for {@code POST /routing/vans/{vanId}/recovery} (§13.5). A station manager dispatches a
 * recovery van to a broken one: the recovery van inherits the broken van's open manifest items and a
 * {@code VAN_BREAKDOWN} is emitted. {@code lastLat}/{@code lastLon} = the broken van's last known
 * position (from telemetry / the driver report), carried on the event. Intraday van add (NFR-3, C18).
 */
public record RecoveryRequest(UUID recoveryVanId, UUID cityId, LocalDate date, double lastLat, double lastLon) {

    public LocalDate dateOrToday(LocalDate today) {
        return date != null ? date : today;
    }
}
