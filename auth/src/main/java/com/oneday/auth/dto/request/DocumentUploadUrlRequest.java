package com.oneday.auth.dto.request;

import com.oneday.auth.domain.OnboardingDocType;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Ask for presigned PUT URLs for one or more documents. The client then PUTs each file straight to R2
 * and submits the returned keys back (see {@link SubmitDocumentsRequest}). {@code contentType} defaults
 * to image/jpeg when null (Aadhaar/PAN/DL photos); pass application/pdf for a PDF.
 */
public record DocumentUploadUrlRequest(
        @NotEmpty List<Item> documents
) {
    public record Item(OnboardingDocType docType, String contentType) {}
}
