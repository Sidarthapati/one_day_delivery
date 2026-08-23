package com.oneday.orders.repository;

/**
 * Spring Data projection for the ageing query — one row per {@code (state, dwell-band)} with the number
 * of live shipments in it. {@code band} is 0..3 (fresh → critical), computed in SQL from
 * {@code now() − COALESCE(last_scan_at, created_at)}. Native-query column aliases ({@code AS state},
 * {@code AS band}, {@code AS cnt}) must match these getters.
 */
public interface AgeingBandCount {
    String getState();
    int getBand();
    long getCnt();
}
