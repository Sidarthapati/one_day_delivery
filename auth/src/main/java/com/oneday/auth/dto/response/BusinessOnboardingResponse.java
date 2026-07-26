package com.oneday.auth.dto.response;

import java.util.UUID;

/**
 * Returned from business onboarding submit — carries the KYC verdicts so the wizard can show
 * per-step status and whether the request will need manual review (any auto-fail).
 */
public record BusinessOnboardingResponse(
        UUID requestId,
        String status,           // PENDING
        boolean gstinVerified,
        String gstinLegalName,
        boolean panVerified,
        boolean needsReview,     // true when any KYC check failed → ADMIN must review
        String kycMessage
) {}
