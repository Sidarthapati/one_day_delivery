package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.AdminCodReconciliationRow;
import com.oneday.orders.dto.AdminDaCashRow;
import com.oneday.orders.dto.BankDepositedRequest;
import com.oneday.orders.dto.CodAccountBalanceResponse;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.ConfirmBankCreditRequest;
import com.oneday.orders.dto.CodCollectionResponse;
import com.oneday.orders.dto.CodRemittanceResponse;
import com.oneday.orders.dto.CreateRemittanceRequest;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.MarkRemittancePaidRequest;
import com.oneday.orders.dto.ReconcileDepositRequest;
import com.oneday.orders.dto.VerifyHandoffRequest;
import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.service.CodCashService;
import com.oneday.orders.service.CodRemittanceService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * ADMIN COD payouts: the worklist of vendors with money to remit, batch creation, and marking a
 * batch paid once the bank transfer clears. Read of any account's collections is allowed here too.
 */
@RestController
@RequestMapping("/api/v1/admin/cod")
class AdminCodController {

    private static final String STATION_MANAGER = "STATION_MANAGER";
    private static final String ADMIN = "ADMIN";

    private final CodRemittanceService cod;
    private final CodCashService codCash;

    AdminCodController(CodRemittanceService cod, CodCashService codCash) {
        this.cod = cod;
        this.codCash = codCash;
    }

    /**
     * The city a station manager is scoped to, or null for an ADMIN (who sees every city). The DA-cash
     * endpoints below pass this to the service, which filters/enforces access by it.
     */
    private static String cityFilter(AuthUserDetails principal) {
        if (ADMIN.equals(principal.getUser().getRole().getName())) {
            return null; // admin sees every city
        }
        String cityId = principal.getUser().getCityId();
        if (cityId == null) {
            // Fail closed: a non-admin with no city assignment must not fall through to the null
            // (= admin, unscoped) branch and read every city's cash.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No city is assigned to your account.");
        }
        return cityId;
    }

    /** Vendors that currently have a payout-available balance, largest first. */
    @GetMapping("/accounts")
    public List<CodAccountBalanceResponse> accounts(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, "ADMIN");
        return cod.accountsWithBalance();
    }

    /** All collections for one account (admin view), optionally filtered by state. */
    @GetMapping("/collections")
    public List<CodCollectionResponse> collections(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam("accountId") UUID accountId,
            @RequestParam(value = "state", required = false) String state) {
        Authz.requireRole(principal, "ADMIN");
        CodCollectionState parsed = (state == null || state.isBlank())
                ? null : CodCollectionState.valueOf(state.trim().toUpperCase());
        return cod.collectionsFor(accountId, parsed);
    }

    /** All remittances (optionally by state, e.g. PENDING) across accounts. */
    @GetMapping("/remittances")
    public List<CodRemittanceResponse> remittances(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "state", required = false) String state) {
        Authz.requireRole(principal, "ADMIN");
        return cod.allRemittances(state);
    }

    @PostMapping("/remittances")
    @ResponseStatus(HttpStatus.CREATED)
    public CodRemittanceResponse create(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody CreateRemittanceRequest request) {
        Authz.requireRole(principal, "ADMIN");
        UUID actorId = UUID.fromString(Authz.requireUserId(principal));
        return cod.createRemittance(request.b2bAccountId(), actorId);
    }

    @PostMapping("/remittances/{id}/pay")
    public CodRemittanceResponse pay(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody MarkRemittancePaidRequest request) {
        Authz.requireRole(principal, "ADMIN");
        return cod.markPaid(id, request.utr());
    }

    /** Pay a remittance out via the payouts provider (RazorpayX). 409 if it can't settle automatically. */
    @PostMapping("/remittances/{id}/payout")
    public CodRemittanceResponse payout(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id) {
        Authz.requireRole(principal, "ADMIN");
        return cod.payout(id);
    }

    // ── DA cash (station manager + admin) ─────────────────────────────────────────

    /** Every DA's live cash-in-hand, most-holding first. Station managers see their own city; admin, all. */
    @GetMapping("/cash/da-balances")
    public List<AdminDaCashRow> daBalances(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return codCash.daCashBalances(cityFilter(principal));
    }

    /**
     * One DA's cash-in-hand passbook (append-only, running balance), newest first. City-gated. Optional
     * {@code from}/{@code to} (ISO-8601 instants) bound the window; omit both for the full trail.
     */
    @GetMapping("/cash/da/{daUserId}/ledger")
    public List<DaCodLedgerEntryResponse> daLedger(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("daUserId") UUID daUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Authz.requireRole(principal, STATION_MANAGER);
        int capped = Math.min(Math.max(1, size), 200);
        return codCash.managerDaLedger(daUserId, from, to,
                PageRequest.of(Math.max(0, page), capped), cityFilter(principal));
    }

    /**
     * One DA's cash-in-hand passbook as a downloadable CSV (G3). Defaults to the last 60 days when no
     * range is given; {@code from}/{@code to} override it. City-gated; capped at 5,000 rows.
     */
    @GetMapping(value = "/cash/da/{daUserId}/ledger/export", produces = "text/csv")
    public ResponseEntity<Resource> exportDaLedger(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("daUserId") UUID daUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Authz.requireRole(principal, STATION_MANAGER);
        Instant fromBound = from != null ? from : Instant.now().minus(60, ChronoUnit.DAYS);
        List<DaCodLedgerEntryResponse> rows = codCash.managerDaLedger(daUserId, fromBound, to,
                PageRequest.of(0, 5000), cityFilter(principal));
        return csvResponse(ledgerCsv(rows), "cod-cash-ledger-" + daUserId + "-" + LocalDate.now() + ".csv");
    }

    /** Per-DA collected-vs-deposited cash + ledger balance, riders with the largest outstanding first. */
    @GetMapping("/cash/reconciliation")
    public List<AdminCodReconciliationRow> reconciliation(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return codCash.reconciliation(cityFilter(principal));
    }

    /** Every declared cash deposit, newest first. */
    @GetMapping("/cash/deposits")
    public List<CodCashDepositResponse> deposits(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        return codCash.allDeposits(cityFilter(principal));
    }

    /** Every declared cash deposit as a downloadable CSV (G3), city-scoped. Newest first. */
    @GetMapping(value = "/cash/deposits/export", produces = "text/csv")
    public ResponseEntity<Resource> exportDeposits(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, STATION_MANAGER);
        List<CodCashDepositResponse> rows = codCash.allDeposits(cityFilter(principal));
        return csvResponse(depositsCsv(rows), "cod-cash-deposits-" + LocalDate.now() + ".csv");
    }

    /** Verify a deposit: matched → RECONCILED, else DISCREPANCY. PATCH (not idempotency-gated). City-gated. */
    @PatchMapping("/cash/deposits/{id}")
    public CodCashDepositResponse reconcile(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody ReconcileDepositRequest request) {
        Authz.requireRole(principal, STATION_MANAGER);
        UUID actorId = UUID.fromString(Authz.requireUserId(principal));
        return codCash.reconcile(id, actorId, request.reconciled(), request.note(), cityFilter(principal));
    }

    // ── Verified custody chain: DA → station → bank (Discussion-3 ix) ──────────────

    /** Station confirms receipt of the DA's cash via the handoff code: DEPOSITED → HANDED_OVER. City-gated. */
    @PostMapping("/cash/deposits/{id}/handoff")
    public CodCashDepositResponse verifyHandoff(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody VerifyHandoffRequest request) {
        Authz.requireRole(principal, STATION_MANAGER);
        UUID actorId = UUID.fromString(Authz.requireUserId(principal));
        return codCash.verifyHandoff(id, request.otp(), actorId, request.countedAmountPaise(), cityFilter(principal));
    }

    /** Station records the bank deposit slip: HANDED_OVER → BANK_DEPOSITED. City-gated. */
    @PostMapping("/cash/deposits/{id}/bank-deposited")
    public CodCashDepositResponse bankDeposited(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody BankDepositedRequest request) {
        Authz.requireRole(principal, STATION_MANAGER);
        return codCash.markBankDeposited(id, request.bankDepositRef(), cityFilter(principal));
    }

    /**
     * Finance confirms the credit landed in our bank: BANK_DEPOSITED → BANK_CONFIRMED (manual ops path;
     * the provider webhook is the automated path). ADMIN-only, no city gate — settles the DA's collections
     * FIFO so they become remittable.
     */
    @PostMapping("/cash/deposits/{id}/bank-confirmed")
    public CodCashDepositResponse bankConfirmed(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable("id") UUID id,
            @Valid @RequestBody ConfirmBankCreditRequest request) {
        Authz.requireRole(principal, "ADMIN");
        return codCash.confirmBankCredit(id, request.bankCreditRef(), null);
    }

    // ── CSV export (G3) — reuses AdminOrdersController's RFC-4180 + injection-safe cell helper ──────

    private static ResponseEntity<Resource> csvResponse(String csv, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String[] LEDGER_CSV_HEADERS = {
            "id", "type", "amount_paise", "balance_after_paise", "reference", "description", "created_at"
    };

    static String ledgerCsv(List<DaCodLedgerEntryResponse> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", LEDGER_CSV_HEADERS)).append("\r\n");
        for (DaCodLedgerEntryResponse r : rows) {
            sb.append(AdminOrdersController.csv(r.id())).append(',')
              .append(AdminOrdersController.csv(r.type())).append(',')
              .append(AdminOrdersController.csv(r.amountPaise())).append(',')
              .append(AdminOrdersController.csv(r.balanceAfterPaise())).append(',')
              .append(AdminOrdersController.csv(r.reference())).append(',')
              .append(AdminOrdersController.csv(r.description())).append(',')
              .append(AdminOrdersController.csv(r.createdAt())).append("\r\n");
        }
        return sb.toString();
    }

    private static final String[] DEPOSITS_CSV_HEADERS = {
            "id", "da_user_id", "amount_paise", "counted_amount_paise", "deposit_ref", "note",
            "status", "reconciled_by", "reconciled_at", "created_at"
    };

    static String depositsCsv(List<CodCashDepositResponse> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", DEPOSITS_CSV_HEADERS)).append("\r\n");
        for (CodCashDepositResponse r : rows) {
            sb.append(AdminOrdersController.csv(r.id())).append(',')
              .append(AdminOrdersController.csv(r.daUserId())).append(',')
              .append(AdminOrdersController.csv(r.amountPaise())).append(',')
              .append(AdminOrdersController.csv(r.countedAmountPaise())).append(',')
              .append(AdminOrdersController.csv(r.depositRef())).append(',')
              .append(AdminOrdersController.csv(r.note())).append(',')
              .append(AdminOrdersController.csv(r.status())).append(',')
              .append(AdminOrdersController.csv(r.reconciledBy())).append(',')
              .append(AdminOrdersController.csv(r.reconciledAt())).append(',')
              .append(AdminOrdersController.csv(r.createdAt())).append("\r\n");
        }
        return sb.toString();
    }
}
