package com.oneday.common.port.dto.kyc;

/**
 * Result of a GSTIN (GST identification number) / KYB verification.
 * {@code verified} is the only field business logic must gate on; the rest are the
 * provider-returned business identity used to pre-fill / cross-check the account.
 */
public record GstinResult(
        boolean verified,
        String gstin,
        String legalName,
        String tradeName,
        String status,      // e.g. "Active"
        String address,
        String message      // human-readable reason when not verified
) {
    public static GstinResult failed(String gstin, String message) {
        return new GstinResult(false, gstin, null, null, null, null, message);
    }
}
