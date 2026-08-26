package com.oneday.orders.repository;

/**
 * Projection for the merchant-analytics destination split — one row per destination city with the
 * shipment count. Alias names in the JPQL ({@code AS city}, {@code AS count}) must match these getters.
 */
public interface CityCount {
    String getCity();
    long getCount();
}
