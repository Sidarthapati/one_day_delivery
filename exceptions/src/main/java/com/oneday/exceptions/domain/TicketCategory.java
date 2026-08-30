package com.oneday.exceptions.domain;

/**
 * What a support ticket is about — set at intake, used to triage/filter the ops queue.
 * Deliberately a small fixed set; OTHER is the catch-all. Nullable on the ticket (untagged is allowed),
 * so adding a value here never breaks existing rows.
 */
public enum TicketCategory {
    /** Late, missing, or failed delivery. */
    DELIVERY,
    /** Pickup didn't happen / was wrong. */
    PICKUP,
    /** "Where is my parcel" — tracking / status. */
    TRACKING,
    /** Wallet, invoice, COD, payments. */
    BILLING,
    /** Damaged or lost parcel. */
    DAMAGE,
    /** Login, KYC, team, account settings. */
    ACCOUNT,
    /** Anything else. */
    OTHER
}
