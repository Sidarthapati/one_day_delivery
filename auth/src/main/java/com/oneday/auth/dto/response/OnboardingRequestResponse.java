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
        Instant createdAt
) {}
