package com.oneday.orders.service.impl;

import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.dto.AdminCodReconciliationRow;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.DaCodCashSummaryResponse;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;
import com.oneday.orders.repository.CodCashDepositRepository;
import com.oneday.orders.repository.CodCollectionRepository;
import com.oneday.orders.service.CodCashService;
import com.oneday.orders.service.CodLedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * @see CodCashService
 */
@Service
class CodCashServiceImpl implements CodCashService {

    private final CodCashDepositRepository deposits;
    private final CodCollectionRepository collections;
    private final CodLedgerService codLedger;

    CodCashServiceImpl(CodCashDepositRepository deposits, CodCollectionRepository collections,
                       CodLedgerService codLedger) {
        this.deposits = deposits;
        this.collections = collections;
        this.codLedger = codLedger;
    }

    @Override
    @Transactional
    public CodCashDepositResponse recordDeposit(UUID daUserId, RecordCodDepositRequest request) {
        String ref = request.depositRef() == null ? null : request.depositRef().trim();
        // Idempotent on (DA, deposit_ref): a repeated submission returns the existing deposit instead
        // of double-counting cash. A concurrent duplicate is caught by the DB unique index (V4_36).
        if (ref != null && !ref.isBlank()) {
            var existing = deposits.findByDaUserIdAndDepositRef(daUserId, ref);
            if (existing.isPresent()) {
                return CodCashDepositResponse.from(existing.get());
            }
        }
        CodCashDeposit d = new CodCashDeposit();
        d.setDaUserId(daUserId);
        d.setAmountPaise(request.amountPaise());
        d.setDepositRef(ref);
        d.setNote(request.note());
        d.setStatus(CodCashDepositState.DEPOSITED);
        CodCashDeposit saved;
        try {
            saved = deposits.saveAndFlush(d);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Lost the race to a concurrent identical submit — return the winner, don't double-post.
            return CodCashDepositResponse.from(
                    deposits.findByDaUserIdAndDepositRef(daUserId, ref).orElseThrow(() -> race));
        }
        // A genuinely new deposit reduces the DA's cash-in-hand (they handed the cash over).
        codLedger.post(daUserId, DaCodLedgerType.DEPOSIT, -saved.getAmountPaise(),
                saved.getDepositRef(), "Cash deposited", daUserId);
        return CodCashDepositResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DaCodCashSummaryResponse daSummary(UUID daUserId) {
        long collected = collections.sumCollectedByDa(daUserId);
        long count = collections.countCollectedByDa(daUserId);
        long deposited = deposits.sumDepositedByDa(daUserId);
        long cashInHand = codLedger.cashInHand(daUserId);
        List<CodCashDepositResponse> rows = deposits.findByDaUserIdOrderByCreatedAtDesc(daUserId)
                .stream().map(CodCashDepositResponse::from).toList();
        return new DaCodCashSummaryResponse(
                daUserId, count, collected, deposited, collected - deposited, cashInHand, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaCodLedgerEntryResponse> daLedger(UUID daUserId) {
        return codLedger.history(daUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminCodReconciliationRow> reconciliation() {
        // Union of DAs that collected cash and DAs that declared deposits.
        Set<UUID> das = new LinkedHashSet<>(collections.findDasWithCollectedCash());
        das.addAll(deposits.findDistinctDaIds());
        return das.stream()
                .map(da -> {
                    long collected = collections.sumCollectedByDa(da);
                    long count = collections.countCollectedByDa(da);
                    long deposited = deposits.sumDepositedByDa(da);
                    return new AdminCodReconciliationRow(da, count, collected, deposited, collected - deposited);
                })
                .sorted(Comparator.comparingLong(AdminCodReconciliationRow::variancePaise).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodCashDepositResponse> allDeposits() {
        return deposits.findAllByOrderByCreatedAtDesc().stream()
                .map(CodCashDepositResponse::from).toList();
    }

    @Override
    @Transactional
    public CodCashDepositResponse reconcile(UUID depositId, UUID adminId, boolean reconciled, String note) {
        CodCashDeposit d = deposits.findById(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found"));
        d.setStatus(reconciled ? CodCashDepositState.RECONCILED : CodCashDepositState.DISCREPANCY);
        d.setReconciledBy(adminId);
        d.setReconciledAt(Instant.now());
        if (note != null && !note.isBlank()) {
            d.setNote(note.trim());
        }
        return CodCashDepositResponse.from(deposits.save(d));
    }
}
