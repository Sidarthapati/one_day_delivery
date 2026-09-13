package com.oneday.orders.dto;

import java.util.List;
import java.util.UUID;

/**
 * A delivery associate's own COD cash position: what they collected vs what they've deposited.
 * {@code cashInHandPaise} is the authoritative running balance from the ledger (cash the DA is still
 * holding); {@code outstandingPaise} is the on-the-fly collected−deposited figure kept for continuity.
 * {@code ceilingPaise} is the configured cash-in-hand ceiling and {@code overCeiling} flags when the DA
 * is holding more than that — a soft warning to deposit (Discussion-4 G2), not a hard gate.
 */
public record DaCodCashSummaryResponse(
        UUID daUserId,
        long collectedCount,
        long collectedPaise,
        long depositedPaise,
        long outstandingPaise,
        long cashInHandPaise,
        long ceilingPaise,
        boolean overCeiling,
        List<CodCashDepositResponse> deposits) {
}
