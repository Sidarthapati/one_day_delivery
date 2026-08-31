package com.oneday.orders.domain;

/** A movement on a delivery associate's COD cash-in-hand ledger. */
public enum DaCodLedgerType {
    /** DA took cash from the buyer on delivery (+ increases cash-in-hand owed to the company). */
    COLLECTION,
    /** DA handed cash in (bank/hub) (− decreases cash-in-hand). */
    DEPOSIT,
    /** Admin correction / shortfall write-off (± either direction). */
    ADJUSTMENT
}
