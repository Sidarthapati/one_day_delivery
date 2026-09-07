package com.oneday.orders.service;

import com.oneday.orders.dto.AdminCodReconciliationRow;
import com.oneday.orders.dto.AdminDaCashRow;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.DaCodCashSummaryResponse;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.DepositRecordedResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * DA COD cash reconciliation: a delivery associate declares the cash they've deposited, and admin
 * reconciles it against the COD cash attributed to that DA on delivery. Separate from
 * {@link CodRemittanceService} (vendor payouts) — this is the money the <em>company</em> is owed by
 * its riders, not the money owed to merchants.
 */
public interface CodCashService {

    // ── DA (own) ────────────────────────────────────────────────────────────────

    /**
     * The DA declares a cash deposit they're about to hand in. Returns the deposit plus a one-time
     * handoff code the DA presents to the station cashier — cash-in-hand only drops once the station
     * verifies receipt (see {@link #verifyHandoff}), not at declaration.
     */
    DepositRecordedResponse recordDeposit(UUID daUserId, RecordCodDepositRequest request);

    /** The DA's own position: collected vs deposited + authoritative cash-in-hand, with deposit history. */
    DaCodCashSummaryResponse daSummary(UUID daUserId);

    /** A page of the DA's cash-in-hand ledger (append-only movement history), newest first. */
    List<DaCodLedgerEntryResponse> daLedger(UUID daUserId, Pageable pageable);

    // ── Station / Admin ─────────────────────────────────────────────────────────

    /**
     * Every DA's live cash-in-hand for the station/admin worklist (#191), most-holding first.
     * {@code cityFilter} scopes the list to one city (a station manager sees their own city); null =
     * every city (admin).
     */
    List<AdminDaCashRow> daCashBalances(String cityFilter);

    /**
     * A page of one DA's cash-in-hand ledger for a manager/admin (#191). {@code cityFilter} enforces
     * access — a manager scoped to a city can't read a DA in another city (403); null = admin, no gate.
     */
    List<DaCodLedgerEntryResponse> managerDaLedger(UUID daUserId, Pageable pageable, String cityFilter);

    /**
     * Per-DA collected-vs-deposited + authoritative ledger balance, riders with the largest outstanding
     * first. {@code cityFilter} scopes the list to one city (a station manager sees their own city);
     * null = every city (admin). A DA whose city can't be resolved is hidden from a scoped manager.
     */
    List<AdminCodReconciliationRow> reconciliation(String cityFilter);

    /**
     * Every declared deposit, newest first. {@code cityFilter} scopes the list to one city (a station
     * manager sees their own city); null = every city (admin). A deposit whose DA's city can't be
     * resolved is hidden from a scoped manager.
     */
    List<CodCashDepositResponse> allDeposits(String cityFilter);

    /**
     * Manager/admin verdict on a deposit: matched → RECONCILED, else DISCREPANCY. {@code cityFilter}
     * enforces access — a manager can only reconcile a deposit whose DA is in their city (403); null =
     * admin, no gate.
     */
    CodCashDepositResponse reconcile(UUID depositId, UUID actorId, boolean reconciled, String note, String cityFilter);

    // ── Verified custody chain: DA → station → bank (Discussion-3 ix) ─────────────

    /**
     * The station cashier confirms receipt of the DA's cash by entering the handoff OTP. Moves the
     * deposit DEPOSITED → HANDED_OVER, records who received it, and only now posts the DA's cash-in-hand
     * deduction (the cash has physically left the rider). {@code cityFilter} gates the station manager to
     * their own city. 422 if the code is wrong/expired.
     */
    CodCashDepositResponse verifyHandoff(UUID depositId, String otp, UUID receivedBy, String cityFilter);

    /** Station records the bank deposit slip: HANDED_OVER → BANK_DEPOSITED. City-gated. */
    CodCashDepositResponse markBankDeposited(UUID depositId, String bankDepositRef, String cityFilter);

    /**
     * Finance (or the provider webhook) confirms the credit landed in the company account:
     * BANK_DEPOSITED → BANK_CONFIRMED. Triggers FIFO settlement of the DA's oldest in-custody collections
     * up to the total cash now confirmed — those collections become remittable to their vendors.
     * {@code cityFilter} null for admin/webhook (no city gate).
     */
    CodCashDepositResponse confirmBankCredit(UUID depositId, String bankCreditRef, String cityFilter);
}
