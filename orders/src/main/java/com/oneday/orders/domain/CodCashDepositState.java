package com.oneday.orders.domain;

/** Lifecycle of a DA's declared cash deposit against COD they collected. */
public enum CodCashDepositState {
    /** Recorded by the DA — cash handed to bank/hub, awaiting admin verification. */
    DEPOSITED,
    /** Admin verified the deposit against what the DA was expected to be holding. */
    RECONCILED,
    /** Admin flagged a mismatch (short/over) — needs follow-up. */
    DISCREPANCY
}
