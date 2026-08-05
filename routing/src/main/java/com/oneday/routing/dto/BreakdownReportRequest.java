package com.oneday.routing.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Body for {@code POST /routing/vans/{vanId}/breakdown} — the van driver reports their own van broke
 * down. {@code cityId} scopes the alert; {@code lat}/{@code lon} are the van's current position (from
 * the phone) so ops can find it. Raises a {@code VAN_BREAKDOWN} alert; ops then dispatches a recovery
 * van via {@code /recovery}. No recovery van here — the driver can't pick one.
 */
public record BreakdownReportRequest(UUID cityId, LocalDate date, Double lat, Double lon) {

    public LocalDate dateOrToday(LocalDate today) {
        return date != null ? date : today;
    }
}
