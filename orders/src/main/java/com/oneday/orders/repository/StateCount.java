package com.oneday.orders.repository;

import com.oneday.common.domain.enums.ShipmentState;

/**
 * Spring Data projection for the grouped-count query — one row per {@link ShipmentState}
 * with the number of shipments currently in it. Alias names in the JPQL ({@code AS state},
 * {@code AS count}) must match these getters.
 */
public interface StateCount {
    ShipmentState getState();
    long getCount();
}
