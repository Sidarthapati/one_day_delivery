package com.oneday.orders.repository;

/**
 * Projection for the merchant-analytics money rollup: total shipping charged (GMV) and total COD
 * value handled, both in paise, for one B2B account over a window. Alias names in the JPQL
 * ({@code AS gmvPaise}, {@code AS codPaise}) must match these getters.
 */
public interface AccountTotals {
    long getGmvPaise();
    long getCodPaise();
}
