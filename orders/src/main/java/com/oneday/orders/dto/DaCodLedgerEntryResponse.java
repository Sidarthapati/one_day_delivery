package com.oneday.orders.dto;

import com.oneday.orders.domain.DaCodLedgerEntry;

import java.time.Instant;
import java.util.UUID;

/** One row of a DA's COD cash-in-hand ledger. Snake_case on the wire (project-wide Jackson strategy). */
public record DaCodLedgerEntryResponse(
        UUID id,
        String type,
        long amountPaise,
        long balanceAfterPaise,
        String reference,
        String description,
        Instant createdAt) {

    public static DaCodLedgerEntryResponse from(DaCodLedgerEntry e) {
        return new DaCodLedgerEntryResponse(e.getId(), e.getType().name(), e.getAmountPaise(),
                e.getBalanceAfterPaise(), e.getReference(), e.getDescription(), e.getCreatedAt());
    }
}
