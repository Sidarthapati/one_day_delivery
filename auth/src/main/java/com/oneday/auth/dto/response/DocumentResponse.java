package com.oneday.auth.dto.response;

import com.oneday.auth.domain.DocumentStatus;
import com.oneday.auth.domain.OnboardingDocType;

import java.time.Instant;
import java.util.UUID;

/**
 * A candidate's uploaded document. {@code downloadUrl} is a short-lived presigned GET so the applicant
 * or reviewer can view the file; it is null when object storage is unavailable.
 */
public record DocumentResponse(
        UUID id,
        OnboardingDocType docType,
        DocumentStatus status,
        String downloadUrl,
        Instant uploadedAt
) {}
