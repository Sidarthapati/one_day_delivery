package com.oneday.orders.service.impl;

import com.oneday.orders.domain.DaCodBalance;
import com.oneday.orders.domain.DaCodLedgerEntry;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.repository.DaCodBalanceRepository;
import com.oneday.orders.repository.DaCodLedgerRepository;
import com.oneday.orders.service.CodLedgerService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class CodLedgerServiceImpl implements CodLedgerService {

    private final DaCodBalanceRepository balances;
    private final DaCodLedgerRepository ledger;

    CodLedgerServiceImpl(DaCodBalanceRepository balances, DaCodLedgerRepository ledger) {
        this.balances = balances;
        this.ledger = ledger;
    }

    @Override
    @Transactional
    public long post(UUID daUserId, DaCodLedgerType type, long signedAmountPaise, String reference,
                     String description, UUID createdBy) {
        // Ensure the balance row exists, then lock it so concurrent postings for this DA serialise and the
        // running balance can't race (mirrors the wallet's lock on b2b_accounts.wallet_balance_paise).
        balances.ensureRow(daUserId);
        DaCodBalance balance = balances.findByIdForUpdate(daUserId).orElseThrow();
        long newBalance = balance.getCashInHandPaise() + signedAmountPaise;
        balance.setCashInHandPaise(newBalance);
        balances.save(balance);

        DaCodLedgerEntry entry = new DaCodLedgerEntry();
        entry.setDaUserId(daUserId);
        entry.setType(type);
        entry.setAmountPaise(signedAmountPaise);
        entry.setBalanceAfterPaise(newBalance);
        entry.setReference(reference);
        entry.setDescription(description);
        entry.setCreatedBy(createdBy);
        ledger.save(entry);
        return newBalance;
    }

    @Override
    @Transactional(readOnly = true)
    public long cashInHand(UUID daUserId) {
        return balances.findById(daUserId).map(DaCodBalance::getCashInHandPaise).orElse(0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaCodLedgerEntryResponse> history(UUID daUserId, Pageable pageable) {
        return ledger.findByDaUserIdOrderByCreatedAtDesc(daUserId, pageable).stream()
                .map(DaCodLedgerEntryResponse::from)
                .toList();
    }
}
