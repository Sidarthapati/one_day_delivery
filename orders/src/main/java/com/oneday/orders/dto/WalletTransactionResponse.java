package com.oneday.orders.dto;

import com.oneday.orders.domain.WalletTransaction;

import java.time.Instant;
import java.util.UUID;

/** One wallet ledger row for the vendor's statement. */
public record WalletTransactionResponse(
        UUID id,
        String type,
        long amountPaise,
        long balanceAfterPaise,
        String reference,
        String description,
        Instant createdAt) {

    public static WalletTransactionResponse from(WalletTransaction t) {
        return new WalletTransactionResponse(
                t.getId(), t.getType().name(), t.getAmountPaise(), t.getBalanceAfterPaise(),
                t.getReference(), t.getDescription(), t.getCreatedAt());
    }
}
