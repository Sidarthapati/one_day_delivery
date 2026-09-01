package com.oneday.orders.service.impl;

import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.service.UserService;
import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;
import com.oneday.orders.domain.DaCodBalance;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.dto.AdminCodReconciliationRow;
import com.oneday.orders.dto.AdminDaCashRow;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.DaCodCashSummaryResponse;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;
import com.oneday.orders.repository.CodCashDepositRepository;
import com.oneday.orders.repository.CodCollectionRepository;
import com.oneday.orders.repository.DaCodBalanceRepository;
import com.oneday.orders.service.CodCashService;
import com.oneday.orders.service.CodLedgerService;
import org.springframework.data.domain.Pageable;
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
    private final DaCodBalanceRepository balances;
    private final UserService userService;

    CodCashServiceImpl(CodCashDepositRepository deposits, CodCollectionRepository collections,
                       CodLedgerService codLedger, DaCodBalanceRepository balances,
                       UserService userService) {
        this.deposits = deposits;
        this.collections = collections;
        this.codLedger = codLedger;
        this.balances = balances;
        this.userService = userService;
    }

    @Override
    @Transactional
    public CodCashDepositResponse recordDeposit(UUID daUserId, RecordCodDepositRequest request) {
        // depositRef is the required idempotency key (@NotBlank on the request). Every deposit is
        // pre-checked, so a retry returns the existing row and never double-posts a ledger movement.
        String ref = request.depositRef().trim();
        var existing = deposits.findByDaUserIdAndDepositRef(daUserId, ref);
        if (existing.isPresent()) {
            return CodCashDepositResponse.from(existing.get());
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
            // A concurrent identical submit won the unique-index race. Don't recover-read here — the
            // transaction is already rollback-only — surface a 409 so the client retries and the
            // pre-check above returns the winning row (200). No double-post.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A deposit with this reference is already being recorded — retry.");
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
    public List<DaCodLedgerEntryResponse> daLedger(UUID daUserId, Pageable pageable) {
        return codLedger.history(daUserId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminDaCashRow> daCashBalances(String cityFilter) {
        return balances.findAllByOrderByCashInHandPaiseDesc().stream()
                .map(b -> {
                    UserResponse u = tryGetUser(b.getDaUserId());
                    return new AdminDaCashRow(
                            b.getDaUserId(),
                            u == null ? null : u.name(),
                            u == null ? null : u.email(),
                            u == null ? null : u.cityId(),
                            b.getCashInHandPaise(),
                            deposits.lastDepositAt(b.getDaUserId()));
                })
                // A city-scoped manager only sees their own city's riders; a DA whose city can't be
                // resolved is hidden from a scoped manager (can't confirm they belong) but shown to admin.
                .filter(row -> cityFilter == null || cityFilter.equals(row.cityId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaCodLedgerEntryResponse> managerDaLedger(UUID daUserId, Pageable pageable, String cityFilter) {
        assertCityAccess(daUserId, cityFilter);
        return codLedger.history(daUserId, pageable);
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
                    UserResponse u = tryGetUser(da);
                    return new AdminCodReconciliationRow(da,
                            u == null ? null : u.name(),
                            u == null ? null : u.email(),
                            count, collected, deposited, collected - deposited,
                            codLedger.cashInHand(da));
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
    public CodCashDepositResponse reconcile(UUID depositId, UUID actorId, boolean reconciled, String note,
                                            String cityFilter) {
        CodCashDeposit d = deposits.findById(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found"));
        assertCityAccess(d.getDaUserId(), cityFilter);
        d.setStatus(reconciled ? CodCashDepositState.RECONCILED : CodCashDepositState.DISCREPANCY);
        d.setReconciledBy(actorId);
        d.setReconciledAt(Instant.now());
        if (note != null && !note.isBlank()) {
            d.setNote(note.trim());
        }
        return CodCashDepositResponse.from(deposits.save(d));
    }

    /** Resolve a user to name/email/city, or null if the record isn't found. Best-effort enrichment. */
    private UserResponse tryGetUser(UUID userId) {
        try {
            return userService.getUser(userId);
        } catch (UserNotFoundException e) {
            return null;
        }
    }

    /**
     * A city-scoped manager may only act on a DA in their own city. {@code cityFilter} null ⇒ admin,
     * no gate. A DA whose city can't be resolved is refused to a scoped manager (fail-closed).
     */
    private void assertCityAccess(UUID daUserId, String cityFilter) {
        if (cityFilter == null) {
            return;
        }
        UserResponse u = tryGetUser(daUserId);
        if (u == null || !cityFilter.equals(u.cityId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This delivery associate isn't in your city.");
        }
    }
}
