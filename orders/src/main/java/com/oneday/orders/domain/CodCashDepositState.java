package com.oneday.orders.domain;

/**
 * Lifecycle of a DA's cash deposit against COD they collected — the verified custody chain from the
 * rider's hand to a confirmed credit in the company bank account (Discussion-3 ix).
 *
 * <pre>
 *   DEPOSITED ──(OTP-verified receipt)──▶ HANDED_OVER ──(bank slip)──▶ BANK_DEPOSITED
 *                                                                            │
 *                                              (finance / provider confirms) ▼
 *                                                                     BANK_CONFIRMED
 * </pre>
 * {@link #DISCREPANCY} is the off-ramp when an amount doesn't match at a verified step.
 */
public enum CodCashDepositState {
    /** Declared by the DA — they say they hold this cash and are handing it in. Not yet verified. */
    DEPOSITED,
    /** The station cashier confirmed receipt of the cash via the handoff OTP. */
    HANDED_OVER,
    /** The station recorded the bank deposit slip — cash is in transit to the bank. */
    BANK_DEPOSITED,
    /** Finance (or the provider webhook) confirmed the credit actually landed in our account. Terminal. */
    BANK_CONFIRMED,
    /** An amount mismatch (short/over) surfaced at a verified step — needs follow-up. */
    DISCREPANCY,
    /**
     * Legacy: the pre-custody-chain admin eyeball verdict (V4_33). Kept for rows created before the
     * verified chain existed; new deposits never enter this state.
     */
    RECONCILED;

    /** True once the cash is confirmed in the company bank account — the gate for remitting a vendor. */
    public boolean isBankConfirmed() {
        return this == BANK_CONFIRMED;
    }
}
