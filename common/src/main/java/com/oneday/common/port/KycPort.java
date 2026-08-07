package com.oneday.common.port;

import com.oneday.common.port.dto.kyc.BankAccountResult;
import com.oneday.common.port.dto.kyc.GstinResult;
import com.oneday.common.port.dto.kyc.PanResult;

/**
 * KYC/KYB verification — the swappable seam over an external provider (Sandbox.co.in in v1),
 * mirroring {@code PaymentPort}/GhaPort. The default bean is a deterministic mock (no external
 * calls, no cost); the real adapter activates on {@code kyc.live=true} with provider credentials.
 *
 * <p>Pilot scope (per docs/B2B/B2B-PORTAL-PLAN.md §8): GSTIN + PAN + bank penny-drop. Aadhaar OTP
 * is deferred behind a flag and intentionally not on this interface yet. e-Invoicing / e-way-bill /
 * GST-return calls are a separate {@code EInvoicePort}, not KYC.</p>
 */
public interface KycPort {

    /** Verify a GSTIN and return the registered business identity. Never null. */
    GstinResult verifyGstin(String gstin);

    /** Verify a PAN, cross-checking against the supplied name. Never null. */
    PanResult verifyPan(String pan, String name);

    /** Penny-drop a bank account for COD remittance, cross-checking the beneficiary name. Never null. */
    BankAccountResult verifyBankAccount(String accountNumber, String ifsc, String beneficiaryName);
}
