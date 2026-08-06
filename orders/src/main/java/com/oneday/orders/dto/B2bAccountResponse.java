package com.oneday.orders.dto;

import java.util.UUID;

/** The caller's B2B account status — GET /api/v1/b2b/accounts/mine. Powers the portal dashboard header. */
public record B2bAccountResponse(
        UUID accountId,
        String accountName,
        String verificationStatus,
        boolean active,
        String gstin,
        boolean gstinVerified,
        boolean panVerified,
        boolean bankVerified,
        String businessType,
        String billingEmail,
        String cityId,
        long creditLimitPaise,
        long outstandingBalancePaise,
        long availableCreditPaise,
        short paymentTermsDays,
        String rejectionReason
) {}
