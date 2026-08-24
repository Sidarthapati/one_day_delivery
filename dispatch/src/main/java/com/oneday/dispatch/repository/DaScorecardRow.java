package com.oneday.dispatch.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-DA scorecard projection for a date: stops completed ({@code done}), failed attempts ({@code failed}),
 * completed on or before their ETA ({@code onTime}), still-open tasks ({@code pending}), and the DA's first
 * assignment time ({@code firstAssigned}, for stops-per-hour). Native-query aliases are camelCase
 * ({@code AS daId / done / failed / onTime / pending / firstAssigned}) so Postgres's lowercase fold matches
 * these getters — same pattern as {@link DaPaceRow}.
 */
public interface DaScorecardRow {
    UUID getDaId();
    long getDone();
    long getFailed();
    long getOnTime();
    long getPending();
    Instant getFirstAssigned();
}
