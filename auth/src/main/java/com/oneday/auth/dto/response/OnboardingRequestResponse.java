package com.oneday.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record OnboardingRequestResponse(
        UUID id,
        String email,
        String name,
        String requestedRole,
        String status,
        String rejectionReason,
        UUID reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        // Business (B2B) onboarding fields — null for non-business requests.
        String companyName,
        String businessType,
        String gstin,
        String pan,
        String billingEmail,
        String cityId,
        Boolean gstinVerified,
        Boolean panVerified,
        String gstinLegalName,
        String kycMessage
) {}
