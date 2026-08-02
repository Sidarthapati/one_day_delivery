package com.oneday.orders.service;

import com.oneday.orders.domain.BankVerificationState;

/**
 * Outbound money movement: verifying a merchant's bank account (penny-drop) and paying COD out to
 * it. This is the seam between our COD ledger and a payouts provider (RazorpayX / Cashfree), mirror
 * of {@link PaymentPort} on the collection side.
 *
 * <p>The default {@code ManualPayoutAdapter} needs no provider — verification is a finance eyeball
 * and payout is a manual bank transfer whose UTR is typed into the admin console. A RazorpayX
 * adapter swaps in via {@code payout.provider=razorpayx} to do real penny-drop + API payouts.
 */
public interface PayoutPort {

    /** A bank account to verify or pay into. */
    record BankAccount(String accountNumber, String ifsc, String beneficiaryName) {}

    /**
     * Result of kicking off bank-account verification.
     *
     * <p>{@code state} is the resulting {@link BankVerificationState}: MANUAL_VERIFIED (manual
     * adapter), PENDING (penny-drop in flight, provider will finalise via webhook), or FAILED.
     * {@code reference} is the provider's validation/fund-account id (null for manual).
     */
    record VerificationOutcome(BankVerificationState state, String reference, String message) {}

    /**
     * A COD payout to make. {@code fundAccountRef} is the provider fund-account id captured during
     * verification (stored on the account); the RazorpayX adapter pays into it, the manual adapter
     * ignores it.
     */
    record PayoutRequest(BankAccount account, String fundAccountRef, long amountPaise, String remittanceRef) {}

    /**
     * Result of a payout. {@code utr} is the bank reference once known (may be null if the provider
     * settles asynchronously — the manual adapter never sets it, the admin supplies it instead).
     * {@code providerRef} is the provider's payout id. {@code settled} is true only when the money
     * has actually moved (manual adapter → false; caller records the UTR by hand).
     */
    record PayoutResult(boolean settled, String utr, String providerRef, String message) {}

    /** Kick off verification of a bank account (penny-drop, or a manual mark). */
    VerificationOutcome verifyBankAccount(BankAccount account, String beneficiaryLegalName);

    /** Pay a COD remittance out to the account. */
    PayoutResult createPayout(PayoutRequest request);
}
