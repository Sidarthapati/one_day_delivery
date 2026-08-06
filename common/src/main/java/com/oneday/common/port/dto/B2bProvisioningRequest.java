package com.oneday.common.port.dto;

import java.util.UUID;

/**
 * Everything M4 (orders) needs to provision a {@code B2bAccount} when M1 (auth) approves a
 * business onboarding. KYC verdicts captured during onboarding travel along so the account
 * lands ACTIVE (all auto-passed) or in MANUAL_REVIEW.
 *
 * @param creditLimitPaise initial credit line; 0 = prepaid-only until a credit line is approved.
 */
public record B2bProvisioningRequest(
        UUID ownerUserId,
        String companyName,
        String businessType,
        String gstin,
        String pan,
        String billingEmail,
        String cityId,
        boolean gstinVerified,
        boolean panVerified,
        long creditLimitPaise,
        short paymentTermsDays
) {}
