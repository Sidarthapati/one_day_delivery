package com.oneday.orders.service;

import com.oneday.orders.dto.MerchantAnalyticsResponse;

import java.util.UUID;

/**
 * Read-only shipping analytics for a single B2B account: volumes, delivery/on-time rates, GMV and a
 * destination split. The merchant self-service counterpart to the ADMIN ops summary
 * ({@link AdminOrderSummaryService}) — always scoped to one owned account, never global.
 */
public interface MerchantAnalyticsService {

    /**
     * @param accountId  the B2B account (ownership already enforced by the caller)
     * @param windowDays null = all-time; otherwise only shipments booked within the last N days
     */
    MerchantAnalyticsResponse forAccount(UUID accountId, Integer windowDays);
}
