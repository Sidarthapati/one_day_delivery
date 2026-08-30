package com.oneday.orders.dto;

import java.util.List;
import java.util.UUID;

/**
 * A delivery associate's own COD cash position: what they collected vs what they've deposited.
 * {@code cashInHandPaise} is the authoritative running balance from the ledger (cash the DA is still
 * holding); {@code outstandingPaise} is the on-the-fly collected−deposited figure kept for continuity.
 */
public record DaCodCashSummaryResponse(
        UUID daUserId,
        long collectedCount,
        long collectedPaise,
        long depositedPaise,
        long outstandingPaise,
        long cashInHandPaise,
        List<CodCashDepositResponse> deposits) {
}
