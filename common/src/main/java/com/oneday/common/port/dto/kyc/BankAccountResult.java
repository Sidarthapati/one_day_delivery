package com.oneday.common.port.dto.kyc;

/**
 * Result of a bank-account penny-drop verification (for COD remittance).
 * {@code beneficiaryName} is the name the bank holds; {@code nameMatch} is the fuzzy
 * match against the name we submitted.
 */
public record BankAccountResult(
        boolean verified,
        String beneficiaryName,
        boolean nameMatch,
        String message
) {
    public static BankAccountResult failed(String message) {
        return new BankAccountResult(false, null, false, message);
    }
}
