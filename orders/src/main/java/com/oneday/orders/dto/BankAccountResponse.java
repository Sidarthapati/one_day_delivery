package com.oneday.orders.dto;

import java.time.Instant;

/**
 * A merchant's payout bank account as shown back to them — the account number is masked (only the
 * last 4 digits), never returned in full.
 */
public record BankAccountResponse(
        boolean onFile,
        String accountMasked,
        String ifsc,
        String beneficiaryName,
        String bankName,
        String verificationState,
        boolean payable,
        Instant verifiedAt,
        String notifyEmails) {

    /** The empty state — no account submitted yet. */
    public static BankAccountResponse none() {
        return new BankAccountResponse(false, null, null, null, null, "NONE", false, null, null);
    }
}
