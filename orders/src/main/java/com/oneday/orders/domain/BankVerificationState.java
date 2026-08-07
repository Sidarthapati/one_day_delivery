package com.oneday.orders.domain;

/**
 * Verification state of a merchant's payout bank account.
 *
 * <p>NONE → the account hasn't been submitted (or was cleared). PENDING → a penny-drop is in
 * flight at the payouts provider. VERIFIED → the provider's ₹1 penny-drop landed and the bank's
 * registered name matched the merchant. MANUAL_VERIFIED → no provider configured; finance
 * eyeballed the details (pilot path). FAILED → the penny-drop bounced or the name didn't match.
 *
 * <p>Only {@link #VERIFIED} / {@link #MANUAL_VERIFIED} accounts may receive a COD payout.
 */
public enum BankVerificationState {
    NONE,
    PENDING,
    VERIFIED,
    MANUAL_VERIFIED,
    FAILED;

    /** True when this account is safe to pay into. */
    public boolean isPayable() {
        return this == VERIFIED || this == MANUAL_VERIFIED;
    }
}
