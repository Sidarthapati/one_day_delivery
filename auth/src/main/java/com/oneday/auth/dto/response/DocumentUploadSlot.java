package com.oneday.auth.dto.response;

import com.oneday.auth.domain.OnboardingDocType;

/** A presigned upload slot: PUT the file bytes to {@code uploadUrl}, then submit {@code objectKey} back. */
public record DocumentUploadSlot(
        OnboardingDocType docType,
        String objectKey,
        String uploadUrl
) {}
