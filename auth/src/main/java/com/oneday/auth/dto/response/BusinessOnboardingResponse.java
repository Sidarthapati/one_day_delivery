package com.oneday.auth.dto.response;

import java.util.UUID;

/**
 * Returned from business onboarding submit — carries the KYC verdicts so the wizard can show
 * per-step status and whether the request will need manual review (any auto-fail).
 */
public record BusinessOnboardingResponse(
        UUID requestId,
        String status,           // APPROVED (auto) or PENDING (review)
        boolean gstinVerified,
        String gstinLegalName,
        boolean panVerified,
        boolean needsReview,     // true when any KYC check failed → ADMIN must review
        boolean autoApproved,    // true when the account was created + provisioned instantly
        String kycMessage
) {}
