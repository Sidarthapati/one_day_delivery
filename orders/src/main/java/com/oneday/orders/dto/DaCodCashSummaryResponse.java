package com.oneday.orders.dto;

import java.util.List;
import java.util.UUID;

/**
 * A delivery associate's own COD cash position: what they collected vs what they've deposited.
 * A positive {@code outstandingPaise} means the DA is still holding cash they owe the company.
 */
public record DaCodCashSummaryResponse(
        UUID daUserId,
        long collectedCount,
        long collectedPaise,
        long depositedPaise,
        long outstandingPaise,
        List<CodCashDepositResponse> deposits) {
}
