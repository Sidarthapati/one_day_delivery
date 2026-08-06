package com.oneday.common.port.dto.einvoice;

/**
 * IRP registration result. {@code registered=false} means the invoice is a valid self-generated
 * tax invoice but not e-registered (pilot default) — perfectly legal below the threshold.
 */
public record EInvoiceResult(
        boolean registered,
        String irn,        // Invoice Reference Number, null when not registered
        String signedQr,   // base64 signed QR, null when not registered
        String message
) {
    public static EInvoiceResult notRegistered(String message) {
        return new EInvoiceResult(false, null, null, message);
    }
}
