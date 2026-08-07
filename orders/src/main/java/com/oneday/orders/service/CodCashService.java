package com.oneday.orders.service;

import com.oneday.orders.dto.AdminCodReconciliationRow;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.DaCodCashSummaryResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;

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

    /** Record a cash deposit the DA has handed in. */
    CodCashDepositResponse recordDeposit(UUID daUserId, RecordCodDepositRequest request);

    /** The DA's own position: collected vs deposited, with their deposit history. */
    DaCodCashSummaryResponse daSummary(UUID daUserId);

    // ── Admin ─────────────────────────────────────────────────────────────────

    /** Per-DA collected-vs-deposited, riders with the largest outstanding cash first. */
    List<AdminCodReconciliationRow> reconciliation();

    /** Every declared deposit, newest first. */
    List<CodCashDepositResponse> allDeposits();

    /** Admin verdict on a deposit: matched → RECONCILED, else DISCREPANCY. */
    CodCashDepositResponse reconcile(UUID depositId, UUID adminId, boolean reconciled, String note);
}
