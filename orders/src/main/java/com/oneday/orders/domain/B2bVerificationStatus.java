package com.oneday.orders.domain;

/**
 * B2B account KYC lifecycle. {@code isActive} on the account is derived from {@code ACTIVE}
 * and is the hard ship/no-ship gate.
 */
public enum B2bVerificationStatus {
    UNVERIFIED,
    KYC_PENDING,
    MANUAL_REVIEW,
    ACTIVE,
    REJECTED
}
