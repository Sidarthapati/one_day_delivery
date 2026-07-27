package com.oneday.orders.service;

import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.dto.CodAccountBalanceResponse;
import com.oneday.orders.dto.CodCollectionResponse;
import com.oneday.orders.dto.CodRemittanceResponse;
import com.oneday.orders.dto.CodSummaryResponse;

import java.util.List;
import java.util.UUID;

/**
 * B2B COD remittance: the buyer-collect ledger and vendor payouts. Collection rows are created at
 * booking (by the B2B booking service) and advanced by the delivery/cancellation lifecycle hook.
 */
public interface CodRemittanceService {

    // ── Lifecycle hooks (driven by shipment state transitions) ─────────────────

    /** Delivery confirmed → mark the shipment's collection COLLECTED. No-op if none / already done. */
    void onDelivered(UUID shipmentId);

    /** Shipment cancelled or returned before delivery → CANCELLED. No-op if none / already collected. */
    void onCancelled(UUID shipmentId);

    // ── Vendor reads (own account) ─────────────────────────────────────────────

    CodSummaryResponse summaryFor(UUID accountId);

    /** All collections for the account, optionally filtered to one state. */
    List<CodCollectionResponse> collectionsFor(UUID accountId, CodCollectionState stateOrNull);

    List<CodRemittanceResponse> remittancesFor(UUID accountId);

    /** A single remittance with the collections it paid out. */
    CodRemittanceResponse remittanceDetail(UUID remittanceId);

    // ── Admin payouts ──────────────────────────────────────────────────────────

    /** Vendors that currently have a payout-available balance. */
    List<CodAccountBalanceResponse> accountsWithBalance();

    /** All remittances across accounts, optionally filtered to one state (admin console). */
    List<CodRemittanceResponse> allRemittances(String stateOrNull);

    /** Batch all of an account's available (COLLECTED, unremitted) COD into a PENDING remittance. */
    CodRemittanceResponse createRemittance(UUID accountId, UUID actorId);

    /** Confirm the bank transfer for a PENDING remittance → PAID; its collections become REMITTED. */
    CodRemittanceResponse markPaid(UUID remittanceId, String utr);
}
