package com.oneday.orders.service;

/**
 * Generates unique, human-readable shipment reference numbers.
 *
 * <p>Format: {@code 1DD-{CITY}-{YYYYMMDD}-{NNNNN}}
 * <br>Example: {@code 1DD-BLR-20260530-00042}
 *
 * <ul>
 *   <li>{@code CITY} — 3-letter origin city code (e.g. BLR, BOM, DEL)</li>
 *   <li>{@code YYYYMMDD} — booking date in IST</li>
 *   <li>{@code NNNNN} — zero-padded 5-digit sequence, resets daily per city</li>
 * </ul>
 *
 * <p>The sequence counter is persisted in {@code shipment_ref_counters} using
 * {@code SELECT FOR UPDATE} to serialise concurrent increments. At high volume
 * (> ~500 bookings/min per city) the row-level lock becomes a bottleneck;
 * the upgrade path is a Redis {@code INCR} + Lua script with async DB sync —
 * documented in M4-ORDERS-DESIGN.md §15.5.</p>
 */
public interface ShipmentRefService {

    /**
     * Atomically generates the next reference number for the given origin city
     * on today's date (IST). Must be called inside an active transaction so that
     * the counter increment is rolled back if the surrounding booking fails.
     *
     * @param originCityCode 3-letter city code (e.g. {@code "BLR"})
     * @return reference string, e.g. {@code "1DD-BLR-20260530-00001"}
     */
    String generateRef(String originCityCode);
}
