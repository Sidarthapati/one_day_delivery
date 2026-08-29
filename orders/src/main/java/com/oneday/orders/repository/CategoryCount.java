package com.oneday.orders.repository;

import java.util.UUID;

/**
 * Projection for the merchant-analytics category split — one row per {@code category_id} with the
 * shipment count ({@code categoryId} is null for untagged parcels). Alias names in the JPQL
 * ({@code AS categoryId}, {@code AS count}) must match these getters. Names are resolved in the service.
 */
public interface CategoryCount {
    UUID getCategoryId();
    long getCount();
}
