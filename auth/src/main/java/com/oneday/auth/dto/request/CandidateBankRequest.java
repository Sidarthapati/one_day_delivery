package com.oneday.auth.dto.request;

/** Wizard step 3 — bank details for payouts. Optional at save; enforced at submit. */
public record CandidateBankRequest(
        String bankAccountNumber,
        String ifsc,
        String accountHolderName
) {}
