package com.oneday.grid.events.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by M3 on topic grid.no_da_alert when no DA is assigned to an active tile
 * for today. Consumed by M5 (Dispatch) and M11 (Exceptions / call-centre queue).
 */
public record NoDaAlertEvent(
        UUID cityId,
        UUID tileId,
        LocalDate validDate,
        String reason,
        Instant alertedAt
) {}
