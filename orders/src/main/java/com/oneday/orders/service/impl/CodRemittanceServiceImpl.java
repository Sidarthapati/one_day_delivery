package com.oneday.orders.service.impl;

import com.oneday.orders.config.CodProperties;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.CodCollection;
import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.domain.CodRemittance;
import com.oneday.orders.domain.CodRemittanceState;
import com.oneday.orders.service.PayoutPort;
import com.oneday.orders.dto.CodAccountBalanceResponse;
import com.oneday.orders.dto.CodCollectionResponse;
import com.oneday.orders.dto.CodRemittanceResponse;
import com.oneday.orders.dto.CodSummaryResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.CodCollectionRepository;
import com.oneday.orders.repository.CodRemittanceRepository;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.service.CodLedgerService;
import com.oneday.orders.service.CodRemittanceService;
import com.oneday.orders.service.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
class CodRemittanceServiceImpl implements CodRemittanceService {

    private static final Logger log = LoggerFactory.getLogger(CodRemittanceServiceImpl.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CodCollectionRepository collections;
    private final CodRemittanceRepository remittances;
    private final B2bAccountRepository accounts;
    private final CodProperties props;
    private final PayoutPort payouts;
    private final Notifier notifier;
    private final CodLedgerService codLedger;

    CodRemittanceServiceImpl(CodCollectionRepository collections,
                             CodRemittanceRepository remittances,
                             B2bAccountRepository accounts,
                             CodProperties props,
                             PayoutPort payouts,
                             Notifier notifier,
                             CodLedgerService codLedger) {
        this.collections = collections;
        this.remittances = remittances;
        this.accounts = accounts;
        this.props = props;
        this.payouts = payouts;
        this.notifier = notifier;
        this.codLedger = codLedger;
    }

    // ── Lifecycle hooks — run in their own transaction (called AFTER_COMMIT) ────

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDelivered(UUID shipmentId, UUID collectedByDaId) {
        collections.findByShipmentId(shipmentId).ifPresent(c -> {
            if (c.getState() == CodCollectionState.AWAITING_COLLECTION) {
                c.setState(CodCollectionState.COLLECTED);
                c.setCollectedAt(Instant.now());
                c.setCollectedByDaId(collectedByDaId);
                collections.save(c);
                // A doorstep DA now holds the buyer's cash → post it to their cash-in-hand ledger. Skip
                // hub self-collect (collectedByDaId null) — no rider is carrying that cash.
                if (collectedByDaId != null) {
                    codLedger.post(collectedByDaId, DaCodLedgerType.COLLECTION, c.getAmountPaise(),
                            c.getShipmentRef(), "COD collected on delivery", collectedByDaId);
                }
                log.info("COD collection {} for shipment {} → COLLECTED ({} paise)",
                        c.getId(), c.getShipmentRef(), c.getAmountPaise());
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCancelled(UUID shipmentId) {
        collections.findByShipmentId(shipmentId).ifPresent(c -> {
            // Only cancel money we never collected. If it was already collected/remitted, leave it.
            if (c.getState() == CodCollectionState.AWAITING_COLLECTION) {
                c.setState(CodCollectionState.CANCELLED);
                collections.save(c);
                log.info("COD collection {} for shipment {} → CANCELLED", c.getId(), c.getShipmentRef());
            }
        });
    }

    // ── Vendor reads ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CodSummaryResponse summaryFor(UUID accountId) {
        long awaiting = collections.sumByAccountAndState(accountId, CodCollectionState.AWAITING_COLLECTION);
        long available = collections.sumRemittable(accountId);
        long collectedTotal = collections.sumByAccountAndState(accountId, CodCollectionState.COLLECTED);
        long inRemittance = Math.max(0, collectedTotal - available);
        long remitted = collections.sumByAccountAndState(accountId, CodCollectionState.REMITTED);
        int awaitingCount = collections.countByB2bAccountIdAndState(accountId, CodCollectionState.AWAITING_COLLECTION);
        int availableCount = collections.findRemittable(accountId).size();
        return new CodSummaryResponse(accountId, awaiting, available, inRemittance, remitted,
                awaitingCount, availableCount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodCollectionResponse> collectionsFor(UUID accountId, CodCollectionState stateOrNull) {
        List<CodCollection> rows = stateOrNull == null
                ? collections.findByB2bAccountIdOrderByCreatedAtDesc(accountId)
                : collections.findByB2bAccountIdAndStateOrderByCreatedAtDesc(accountId, stateOrNull);
        return rows.stream().map(CodRemittanceServiceImpl::toCollection).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodRemittanceResponse> remittancesFor(UUID accountId) {
        return remittances.findByB2bAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(r -> toRemittance(r, null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CodRemittanceResponse remittanceDetail(UUID remittanceId) {
        CodRemittance r = remittances.findById(remittanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remittance not found"));
        List<CodCollectionResponse> items = collections.findByRemittanceIdOrderByCreatedAtDesc(remittanceId)
                .stream().map(CodRemittanceServiceImpl::toCollection).toList();
        return toRemittance(r, items);
    }

    // ── Admin payouts ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CodAccountBalanceResponse> accountsWithBalance() {
        return collections.findAccountsWithRemittableBalance().stream()
                .map(accountId -> {
                    String name = accounts.findById(accountId)
                            .map(a -> a.getAccountName()).orElse(null);
                    return new CodAccountBalanceResponse(accountId, name,
                            collections.sumRemittable(accountId),
                            collections.findRemittable(accountId).size());
                })
                .sorted(Comparator.comparingLong(CodAccountBalanceResponse::availableToRemitPaise).reversed())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodRemittanceResponse> allRemittances(String stateOrNull) {
        List<CodRemittance> rows = (stateOrNull == null || stateOrNull.isBlank())
                ? remittances.findAllByOrderByCreatedAtDesc()
                : remittances.findByStateOrderByCreatedAtDesc(parseRemittanceState(stateOrNull));
        return rows.stream().map(r -> toRemittance(r, null)).toList();
    }

    private static CodRemittanceState parseRemittanceState(String s) {
        try {
            return CodRemittanceState.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown remittance state: " + s);
        }
    }

    @Override
    @Transactional
    public CodRemittanceResponse createRemittance(UUID accountId, UUID actorId) {
        // Lock the account row for the duration: two concurrent create calls would otherwise both read
        // the same remittable collections and each cut a remittance for the SAME money (vuln-0017).
        // The loser blocks here, then finds nothing remittable and gets a clean CONFLICT below.
        B2bAccount account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        // A payout needs a verified bank account on file — you can't remit into thin air.
        if (account.getBankVerificationState() == null || !account.getBankVerificationState().isPayable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No verified bank account on file for this vendor — ask them to add their payout account first");
        }
        List<CodCollection> batch = collections.findRemittable(accountId);
        if (batch.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No COD available to remit for this account");
        }
        long gross = batch.stream().mapToLong(CodCollection::getAmountPaise).sum();
        long fee = props.feeOn(gross);
        long net = gross - fee;

        Instant periodStart = batch.stream().map(CodCollection::getCollectedAt)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant periodEnd = batch.stream().map(CodCollection::getCollectedAt)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        CodRemittance r = new CodRemittance();
        r.setReference("RMT/" + financialYear() + "/" + String.format("%06d", remittances.nextRemittanceSequence()));
        r.setB2bAccountId(accountId);
        r.setGrossPaise(gross);
        r.setFeePaise(fee);
        r.setNetPaise(net);
        r.setCollectionCount(batch.size());
        r.setState(CodRemittanceState.PENDING);
        r.setPeriodStart(periodStart);
        r.setPeriodEnd(periodEnd);
        r.setCreatedBy(actorId);
        r = remittances.save(r);

        for (CodCollection c : batch) {
            c.setRemittanceId(r.getId());   // stays COLLECTED until the payout is confirmed
            collections.save(c);
        }
        log.info("Created remittance {} for account {}: {} collections, gross {} fee {} net {}",
                r.getReference(), accountId, batch.size(), gross, fee, net);

        List<CodCollectionResponse> items = batch.stream().map(CodRemittanceServiceImpl::toCollection).toList();
        return toRemittance(r, items);
    }

    @Override
    @Transactional
    public CodRemittanceResponse markPaid(UUID remittanceId, String utr) {
        // Lock the row so a concurrent markPaid/payout can't both pass the PENDING check and
        // double-remit / double-notify (unflagged sibling of vuln-0017).
        CodRemittance r = remittances.findByIdForUpdate(remittanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remittance not found"));
        if (r.getState() != CodRemittanceState.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Remittance is not PENDING");
        }
        r.setState(CodRemittanceState.PAID);
        r.setUtr(utr);
        r.setPaidAt(Instant.now());
        r = remittances.save(r);

        List<CodCollection> items = collections.findByRemittanceIdOrderByCreatedAtDesc(remittanceId);
        for (CodCollection c : items) {
            c.setState(CodCollectionState.REMITTED);
            collections.save(c);
        }
        log.info("Remittance {} → PAID (utr {}), {} collections REMITTED", r.getReference(), utr, items.size());

        // Best-effort merchant confirmation (log-sink until a provider is wired).
        final String reference = r.getReference();
        final long netPaise = r.getNetPaise();
        accounts.findById(r.getB2bAccountId())
                .ifPresent(a -> notifier.remittancePaid(a, reference, netPaise, utr));
        return toRemittance(r, items.stream().map(CodRemittanceServiceImpl::toCollection).toList());
    }

    @Override
    @Transactional
    public CodRemittanceResponse payout(UUID remittanceId) {
        CodRemittance r = remittances.findByIdForUpdate(remittanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remittance not found"));
        if (r.getState() != CodRemittanceState.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Remittance is not PENDING");
        }
        B2bAccount a = accounts.findById(r.getB2bAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        if (a.getBankVerificationState() == null || !a.getBankVerificationState().isPayable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vendor's bank account is not verified");
        }

        PayoutPort.PayoutResult result = payouts.createPayout(new PayoutPort.PayoutRequest(
                new PayoutPort.BankAccount(a.getBankAccountNumber(), a.getBankIfsc(), a.getBankBeneficiaryName()),
                a.getBankPennyDropRef(), r.getNetPaise(), r.getReference()));

        if (!result.settled() || result.utr() == null) {
            // Manual provider, or the payout only queued — the admin records the real UTR by hand.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payout was not settled automatically (" + result.message() + ") — record the UTR manually once the transfer clears");
        }
        return markPaid(remittanceId, result.utr());
    }

    // ── Mapping ──────────────────────────────────────────────────────────────────

    private static CodCollectionResponse toCollection(CodCollection c) {
        return new CodCollectionResponse(c.getId(), c.getShipmentRef(), c.getAmountPaise(),
                c.getState().name(), c.getCollectedAt(), c.getRemittanceId(), c.getCreatedAt());
    }

    private static CodRemittanceResponse toRemittance(CodRemittance r, List<CodCollectionResponse> items) {
        return new CodRemittanceResponse(r.getId(), r.getReference(), r.getB2bAccountId(),
                r.getGrossPaise(), r.getFeePaise(), r.getNetPaise(), r.getCollectionCount(),
                r.getState().name(), r.getUtr(), r.getPeriodStart(), r.getPeriodEnd(),
                r.getNotes(), r.getCreatedAt(), r.getPaidAt(), items);
    }

    private static String financialYear() {
        LocalDate d = LocalDate.ofInstant(Instant.now(), IST);
        int startYear = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }
}
