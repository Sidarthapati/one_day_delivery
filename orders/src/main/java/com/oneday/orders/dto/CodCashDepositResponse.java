package com.oneday.orders.dto;

import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;

import java.time.Instant;
import java.util.UUID;

/**
 * A DA's COD cash deposit. {@code amountPaise} is the DA's declared figure; {@code countedAmountPaise}
 * is the station's independent count at handoff (null until then) — a mismatch means {@code status} is
 * DISCREPANCY (Discussion-4 G1).
 */
public record CodCashDepositResponse(
        UUID id,
        UUID daUserId,
        Long amountPaise,
        Long countedAmountPaise,
        String depositRef,
        String note,
        CodCashDepositState status,
        UUID reconciledBy,
        Instant reconciledAt,
        Instant createdAt) {

    public static CodCashDepositResponse from(CodCashDeposit d) {
        return new CodCashDepositResponse(
                d.getId(), d.getDaUserId(), d.getAmountPaise(), d.getCountedAmountPaise(),
                d.getDepositRef(), d.getNote(),
                d.getStatus(), d.getReconciledBy(), d.getReconciledAt(), d.getCreatedAt());
    }
}
