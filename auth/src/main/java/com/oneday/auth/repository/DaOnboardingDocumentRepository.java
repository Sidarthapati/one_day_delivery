package com.oneday.auth.repository;

import com.oneday.auth.domain.DaOnboardingDocument;
import com.oneday.auth.domain.OnboardingDocType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaOnboardingDocumentRepository extends JpaRepository<DaOnboardingDocument, UUID> {

    List<DaOnboardingDocument> findByCandidateIdOrderByDocType(UUID candidateId);

    Optional<DaOnboardingDocument> findByCandidateIdAndDocType(UUID candidateId, OnboardingDocType docType);
}
