package com.oneday.orders.domain;

/**
 * Per-member KYC state within a B2B account (Discussion-2 xii). Skipping is allowed, so a member stays
 * {@code UNVERIFIED} (a label) until they verify their PAN. Separate from account-level KYB.
 */
public enum MemberKycStatus {
    UNVERIFIED,
    VERIFIED
}
