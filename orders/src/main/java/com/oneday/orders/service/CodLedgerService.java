package com.oneday.orders.service;

import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * The per-DA COD cash-in-hand ledger — an append-only record of the cash a delivery associate has
 * collected (and not yet deposited), with a running balance. Mirrors the merchant wallet ledger.
 * Separate concern from {@link CodRemittanceService} (money owed to merchants).
 */
public interface CodLedgerService {

    /**
     * Post one movement atomically: lock the DA's balance, apply {@code signedAmountPaise}
     * (+ collection, − deposit), write the ledger entry with the resulting balance, and update the
     * running balance. Returns the balance after this entry.
     */
    long post(UUID daUserId, DaCodLedgerType type, long signedAmountPaise, String reference,
              String description, UUID createdBy);

    /** The DA's current cash-in-hand (0 if they have no ledger yet). */
    long cashInHand(UUID daUserId);

    /** A page of the DA's ledger history, newest first. */
    List<DaCodLedgerEntryResponse> history(UUID daUserId, Pageable pageable);
}
