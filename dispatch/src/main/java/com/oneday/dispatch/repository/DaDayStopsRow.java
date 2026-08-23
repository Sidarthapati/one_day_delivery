package com.oneday.dispatch.repository;

import java.time.LocalDate;

/**
 * Per-day stops for one DA — the mini history/trend. Native aliases {@code AS day / done / failed} map
 * to these getters (camelCase, so Postgres's lowercase fold binds — same pattern as {@link DaPaceRow}).
 */
public interface DaDayStopsRow {
    LocalDate getDay();
    long getDone();
    long getFailed();
}
