package com.oneday.orders.service;

import com.oneday.orders.dto.ShipmentAgeingStats;
import com.oneday.orders.dto.ShipmentSummaryStats;

/**
 * Aggregate parcel counts for the ops monitoring dashboard — one grouped query instead of the
 * many per-state list calls the console used to fire. The read-only, count-only counterpart to
 * {@link AdminOrderQueryService}.
 */
public interface AdminOrderSummaryService {

    /**
     * @param cityScope null → all cities (ADMIN oversight); otherwise restrict to shipments whose
     *                  origin OR destination is this city (a station manager's own city), the same
     *                  rule {@link AdminOrderQueryService#listShipments} uses.
     * @return per-bucket and per-state counts plus the in-scope total
     */
    ShipmentSummaryStats summary(String cityScope);

    /**
     * Ageing rollup: live (non-terminal) shipments grouped by {@code OpsBucket} stage and dwell band
     * (time since last scan), so ops can spot parcels stuck before a flight/cron cutoff. Same city-scope
     * rule as {@link #summary}.
     */
    ShipmentAgeingStats ageing(String cityScope);
}
