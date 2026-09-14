package com.oneday.auth.dto.request;

import com.oneday.auth.domain.OnboardingDocType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** After PUTting the files to R2, the client submits the object keys to record the documents. */
public record SubmitDocumentsRequest(
        @NotEmpty List<Item> documents
) {
    public record Item(@NotNull OnboardingDocType docType, @NotBlank String objectKey) {}
}
