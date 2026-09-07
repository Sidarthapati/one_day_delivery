package com.oneday.orders.service.impl;

import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.service.UserService;
import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;
import com.oneday.orders.domain.CodCollection;
import com.oneday.orders.domain.CodCollectionSettlementState;
import com.oneday.orders.domain.DaCodBalance;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.dto.AdminCodReconciliationRow;
import com.oneday.orders.dto.AdminDaCashRow;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.DaCodCashSummaryResponse;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.DepositRecordedResponse;
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
    private final com.oneday.orders.service.CashHandoffOtpService handoffOtp;

    CodCashServiceImpl(CodCashDepositRepository deposits, CodCollectionRepository collections,
                       CodLedgerService codLedger, DaCodBalanceRepository balances,
                       UserService userService,
                       com.oneday.orders.service.CashHandoffOtpService handoffOtp) {
        this.deposits = deposits;
        this.collections = collections;
        this.codLedger = codLedger;
        this.balances = balances;
        this.userService = userService;
        this.handoffOtp = handoffOtp;
    }

    @Override
    @Transactional
    public DepositRecordedResponse recordDeposit(UUID daUserId, RecordCodDepositRequest request) {
        // depositRef is the required idempotency key (@NotBlank on the request). Every deposit is
        // pre-checked, so a retry returns the existing row (with a fresh handoff code) and never
        // double-posts a ledger movement.
        String ref = request.depositRef().trim();
        var existing = deposits.findByDaUserIdAndDepositRef(daUserId, ref);
        if (existing.isPresent()) {
            // Re-issue a handoff code only while the deposit is still awaiting receipt.
            String otp = existing.get().getStatus() == CodCashDepositState.DEPOSITED
                    ? handoffOtp.generate(existing.get().getId()) : null;
            return new DepositRecordedResponse(CodCashDepositResponse.from(existing.get()), otp);
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
        // No ledger movement yet: the DA still holds the cash at declaration. Cash-in-hand only drops
        // when the station verifies receipt (verifyHandoff) — that's what makes the deposit "verified".
        String otp = handoffOtp.generate(saved.getId());
        return new DepositRecordedResponse(CodCashDepositResponse.from(saved), otp);
    }

    @Override
    @Transactional
    public CodCashDepositResponse verifyHandoff(UUID depositId, String otp, UUID receivedBy, String cityFilter) {
        CodCashDeposit d = deposits.findByIdForUpdate(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found"));
        assertCityAccess(d.getDaUserId(), cityFilter);
        if (d.getStatus() != CodCashDepositState.DEPOSITED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This deposit isn't awaiting handoff (it is " + d.getStatus() + ").");
        }
        handoffOtp.verify(depositId, otp);   // throws 422 on wrong/expired/used code
        d.setStatus(CodCashDepositState.HANDED_OVER);
        d.setReceivedBy(receivedBy);
        d.setHandedOverAt(Instant.now());
        // The cash has physically left the rider — post the cash-in-hand deduction now (not at declaration).
        codLedger.post(d.getDaUserId(), DaCodLedgerType.DEPOSIT, -d.getAmountPaise(),
                d.getDepositRef(), "Cash handed to station", receivedBy);
        return CodCashDepositResponse.from(deposits.save(d));
    }

    @Override
    @Transactional
    public CodCashDepositResponse markBankDeposited(UUID depositId, String bankDepositRef, String cityFilter) {
        CodCashDeposit d = deposits.findByIdForUpdate(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found"));
        assertCityAccess(d.getDaUserId(), cityFilter);
        if (d.getStatus() != CodCashDepositState.HANDED_OVER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This deposit isn't ready to bank (it is " + d.getStatus() + ").");
        }
        if (bankDepositRef == null || bankDepositRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bank deposit slip reference is required.");
        }
        d.setStatus(CodCashDepositState.BANK_DEPOSITED);
        d.setBankDepositRef(bankDepositRef.trim());
        d.setBankDepositedAt(Instant.now());
        return CodCashDepositResponse.from(deposits.save(d));
    }

    @Override
    @Transactional
    public CodCashDepositResponse confirmBankCredit(UUID depositId, String bankCreditRef, String cityFilter) {
        CodCashDeposit d = deposits.findByIdForUpdate(depositId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deposit not found"));
        assertCityAccess(d.getDaUserId(), cityFilter);
        if (d.getStatus() != CodCashDepositState.BANK_DEPOSITED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This deposit isn't awaiting bank confirmation (it is " + d.getStatus() + ").");
        }
        d.setStatus(CodCashDepositState.BANK_CONFIRMED);
        d.setBankCreditRef(bankCreditRef == null ? null : bankCreditRef.trim());
        d.setBankConfirmedAt(Instant.now());
        deposits.save(d);
        settleFifo(d.getDaUserId());   // this DA's oldest in-custody collections become remittable
        return CodCashDepositResponse.from(d);
    }

    /**
     * Cash is fungible, so a DA's total bank-confirmed deposits settle their oldest still-in-custody
     * collections first. Settleable = Σ(confirmed deposits) − Σ(already-settled collections); walk the
     * oldest IN_CUSTODY collections (locked) and flip whole collections to BANK_SETTLED while the running
     * total stays within settleable. A collection larger than the remaining headroom waits for the next
     * confirmed deposit — never partially settled.
     */
    private void settleFifo(UUID daUserId) {
        long settleable = deposits.sumBankConfirmedByDa(daUserId) - collections.sumBankSettledByDa(daUserId);
        if (settleable <= 0) {
            return;
        }
        Instant now = Instant.now();
        long used = 0;
        for (CodCollection c : collections.findInCustodyByDaForUpdate(daUserId)) {
            if (used + c.getAmountPaise() > settleable) {
                break;   // FIFO: stop at the first collection the confirmed cash can't fully cover
            }
            c.setSettlementState(CodCollectionSettlementState.BANK_SETTLED);
            c.setBankSettledAt(now);
            collections.save(c);
            used += c.getAmountPaise();
        }
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
    public List<AdminCodReconciliationRow> reconciliation(String cityFilter) {
        // Union of DAs that collected cash and DAs that declared deposits.
        Set<UUID> das = new LinkedHashSet<>(collections.findDasWithCollectedCash());
        das.addAll(deposits.findDistinctDaIds());
        return das.stream()
                .map(da -> {
                    UserResponse u = tryGetUser(da);
                    // A city-scoped manager only sees their own city's riders; a DA whose city can't be
                    // resolved is hidden from a scoped manager (fail-closed) but shown to admin.
                    if (cityFilter != null && (u == null || !cityFilter.equals(u.cityId()))) {
                        return null;
                    }
                    long collected = collections.sumCollectedByDa(da);
                    long count = collections.countCollectedByDa(da);
                    long deposited = deposits.sumDepositedByDa(da);
                    return new AdminCodReconciliationRow(da,
                            u == null ? null : u.name(),
                            u == null ? null : u.email(),
                            count, collected, deposited, collected - deposited,
                            codLedger.cashInHand(da));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(AdminCodReconciliationRow::variancePaise).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodCashDepositResponse> allDeposits(String cityFilter) {
        // Resolve each DA's city at most once; a deposit whose DA isn't in the manager's city (or
        // can't be resolved) is hidden from a scoped manager but shown to admin (null filter).
        java.util.Map<UUID, Boolean> inCity = new java.util.HashMap<>();
        return deposits.findAllByOrderByCreatedAtDesc().stream()
                .filter(d -> cityFilter == null
                        || inCity.computeIfAbsent(d.getDaUserId(), id -> {
                            UserResponse u = tryGetUser(id);
                            return u != null && cityFilter.equals(u.cityId());
                        }))
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
