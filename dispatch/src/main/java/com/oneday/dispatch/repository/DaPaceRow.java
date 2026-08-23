package com.oneday.dispatch.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-DA pace projection for the execution view: stops completed today ({@code done}), completed in the
 * last hour ({@code lastHour} — the current pace), still-open tasks ({@code pending}), and when the DA's
 * first task was assigned ({@code firstAssigned}, for the average-per-hour). Native-query aliases are
 * camelCase ({@code AS daId}, {@code done}, {@code lastHour}, {@code pending}, {@code firstAssigned}) so
 * Postgres's lowercase fold matches these getters — the pattern the auth DA-summary projections use.
 */
public interface DaPaceRow {
    UUID getDaId();
    long getDone();
    long getLastHour();
    long getPending();
    Instant getFirstAssigned();
}
