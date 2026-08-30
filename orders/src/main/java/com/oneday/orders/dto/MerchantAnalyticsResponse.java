package com.oneday.orders.dto;

import java.util.List;

/**
 * Merchant-facing shipping analytics for one B2B account over a window (null {@code windowDays} =
 * all-time). Field names serialise to snake_case via the global Jackson config
 * ({@code totalShipments} → {@code total_shipments}). Percentages are 0–100 ints, or null when there
 * is nothing to rate (e.g. no delivered-with-ETA parcels yet), so the UI can show "—" rather than 0%.
 *
 * @param windowDays        the window in days, or null for all-time
 * @param totalShipments    every shipment booked in the window
 * @param delivered         reached a delivered terminal state (DROPPED or HUB_COLLECTED)
 * @param inTransit         still moving (booked through the network, not yet terminal)
 * @param cancelled         cancelled shipments
 * @param rto               returned-to-origin (initiated, in transit, or completed)
 * @param deliveryRatePct   delivered ÷ (total − cancelled), or null if nothing to rate
 * @param rtoRatePct        returned-to-origin ÷ (total − cancelled), or null if nothing to rate
 * @param onTimePct         delivered on/before promised ETA ÷ delivered-with-ETA, or null if none
 * @param gmvPaise          total shipping charged, in paise
 * @param codValuePaise     total COD goods-value handled, in paise
 * @param avgShipmentPaise  gmvPaise ÷ totalShipments (0 when no shipments)
 * @param topDestinations   busiest destination cities first
 * @param categorySplit     shipment count per merchant category, busiest first (untagged → "Uncategorised")
 */
public record MerchantAnalyticsResponse(
        Integer windowDays,
        long totalShipments,
        long delivered,
        long inTransit,
        long cancelled,
        long rto,
        Integer deliveryRatePct,
        Integer rtoRatePct,
        Integer onTimePct,
        long gmvPaise,
        long codValuePaise,
        long avgShipmentPaise,
        List<DestinationCount> topDestinations,
        List<CategoryCount> categorySplit) {

    /** One destination city with its shipment count. */
    public record DestinationCount(String city, long count) {}

    /** One merchant category with its shipment count. */
    public record CategoryCount(String category, long count) {}
}
