package com.oneday.orders.service;

/**
 * Generates unique, human-readable order reference numbers — the parent of the shipment refs.
 *
 * <p>Format: {@code 1DD-ORD-{CITY}-{YYYYMMDD}-{NNNNN}}
 * <br>Example: {@code 1DD-ORD-BLR-20260824-00042}
 *
 * <p>Mirrors {@link ShipmentRefService}: a per-(city, date) counter serialised with
 * {@code SELECT FOR UPDATE}, called inside the caller's transaction (MANDATORY).</p>
 */
public interface OrderRefService {

    /** Atomically generates the next order ref for the given origin city on today's date (IST). */
    String generateRef(String originCityCode);
}
