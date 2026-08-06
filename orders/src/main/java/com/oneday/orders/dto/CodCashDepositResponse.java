package com.oneday.orders.dto;

import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;

import java.time.Instant;
import java.util.UUID;

/** A DA's declared COD cash deposit. */
public record CodCashDepositResponse(
        UUID id,
        UUID daUserId,
        Long amountPaise,
        String depositRef,
        String note,
        CodCashDepositState status,
        UUID reconciledBy,
        Instant reconciledAt,
        Instant createdAt) {

    public static CodCashDepositResponse from(CodCashDeposit d) {
        return new CodCashDepositResponse(
                d.getId(), d.getDaUserId(), d.getAmountPaise(), d.getDepositRef(), d.getNote(),
                d.getStatus(), d.getReconciledBy(), d.getReconciledAt(), d.getCreatedAt());
    }
}
