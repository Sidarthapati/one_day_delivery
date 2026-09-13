package com.oneday.auth.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A document an onboarding candidate uploaded — a pointer (R2 object key) plus its review status. */
@Getter
@Setter
@Entity
@Table(name = "da_onboarding_document")
public class DaOnboardingDocument extends BaseEntity {

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private OnboardingDocType docType;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();
}
