package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.DaOnboardingDocument;
import com.oneday.auth.domain.DocumentStatus;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.request.DocumentUploadUrlRequest;
import com.oneday.auth.dto.request.SubmitDocumentsRequest;
import com.oneday.auth.dto.response.DocumentResponse;
import com.oneday.auth.dto.response.DocumentUploadSlot;
import com.oneday.auth.exception.OnboardingCandidateNotFoundException;
import com.oneday.auth.exception.OnboardingValidationException;
import com.oneday.auth.exception.StorageUnavailableException;
import com.oneday.auth.repository.DaOnboardingCandidateRepository;
import com.oneday.auth.repository.DaOnboardingDocumentRepository;
import com.oneday.auth.service.OnboardingDocumentService;
import com.oneday.common.port.ObjectStoragePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class OnboardingDocumentServiceImpl implements OnboardingDocumentService {

    private static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    private final DaOnboardingCandidateRepository candidateRepository;
    private final DaOnboardingDocumentRepository documentRepository;
    // Implemented in `common` (R2) and only present in the assembled app; absent in the auth-only
    // context (matches how B2bProvisioningPort is wired). When absent, upload endpoints return 503.
    private final ObjectProvider<ObjectStoragePort> storageProvider;

    OnboardingDocumentServiceImpl(DaOnboardingCandidateRepository candidateRepository,
                                  DaOnboardingDocumentRepository documentRepository,
                                  ObjectProvider<ObjectStoragePort> storageProvider) {
        this.candidateRepository = candidateRepository;
        this.documentRepository = documentRepository;
        this.storageProvider = storageProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentUploadSlot> presignUploads(String inviteToken, DocumentUploadUrlRequest request) {
        var candidate = requireEditable(inviteToken);
        ObjectStoragePort storage = requireStorage();
        return request.documents().stream().map(item -> {
            String contentType = item.contentType() == null ? DEFAULT_CONTENT_TYPE : item.contentType();
            String key = buildKey(candidate.getId(), item.docType(), contentType);
            String uploadUrl = storage.presignPut(key, contentType, UPLOAD_TTL);
            return new DocumentUploadSlot(item.docType(), key, uploadUrl);
        }).toList();
    }

    @Override
    @Transactional
    public List<DocumentResponse> submitDocuments(String inviteToken, SubmitDocumentsRequest request) {
        var candidate = requireEditable(inviteToken);
        ObjectStoragePort storage = requireStorage();
        for (var item : request.documents()) {
            if (!storage.exists(item.objectKey())) {
                throw new OnboardingValidationException(
                        "Uploaded file not found for " + item.docType() + " — upload before submitting");
            }
            // One row per (candidate, doc type): re-uploading a type replaces its key.
            var doc = documentRepository.findByCandidateIdAndDocType(candidate.getId(), item.docType())
                    .orElseGet(() -> {
                        var d = new DaOnboardingDocument();
                        d.setCandidateId(candidate.getId());
                        d.setDocType(item.docType());
                        return d;
                    });
            doc.setObjectKey(item.objectKey());
            doc.setStatus(DocumentStatus.UPLOADED);
            doc.setUploadedAt(Instant.now());
            documentRepository.save(doc);
        }
        return listByCandidateId(candidate.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> listByToken(String inviteToken) {
        return listByCandidateId(requireByToken(inviteToken).getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> listByCandidateId(UUID candidateId) {
        ObjectStoragePort storage = storageProvider.getIfAvailable();
        boolean available = storage != null && storage.isAvailable();
        return documentRepository.findByCandidateIdOrderByDocType(candidateId).stream()
                .map(d -> new DocumentResponse(
                        d.getId(), d.getDocType(), d.getStatus(),
                        available ? storage.presignGet(d.getObjectKey(), DOWNLOAD_TTL) : null,
                        d.getUploadedAt()))
                .toList();
    }

    @Override
    @Transactional
    public DocumentResponse setStatus(UUID candidateId, UUID documentId, DocumentStatus status) {
        var doc = documentRepository.findById(documentId)
                .filter(d -> d.getCandidateId().equals(candidateId))
                .orElseThrow(() -> new OnboardingCandidateNotFoundException("Document not found: " + documentId));
        doc.setStatus(status);
        documentRepository.save(doc);
        ObjectStoragePort storage = storageProvider.getIfAvailable();
        boolean available = storage != null && storage.isAvailable();
        return new DocumentResponse(doc.getId(), doc.getDocType(), doc.getStatus(),
                available ? storage.presignGet(doc.getObjectKey(), DOWNLOAD_TTL) : null, doc.getUploadedAt());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private DaOnboardingCandidate requireByToken(String token) {
        return candidateRepository.findByInviteToken(token)
                .orElseThrow(() -> new OnboardingCandidateNotFoundException("Invalid or expired invite"));
    }

    private DaOnboardingCandidate requireEditable(String token) {
        var candidate = requireByToken(token);
        if (candidate.getStatus() != OnboardingStatus.DRAFT) {
            throw new OnboardingValidationException(
                    "Documents can only be uploaded before submission (status " + candidate.getStatus() + ")");
        }
        return candidate;
    }

    private ObjectStoragePort requireStorage() {
        ObjectStoragePort storage = storageProvider.getIfAvailable();
        if (storage == null || !storage.isAvailable()) {
            throw new StorageUnavailableException("Document storage is not available");
        }
        return storage;
    }

    private static String buildKey(UUID candidateId, Object docType, String contentType) {
        return "onboarding/" + candidateId + "/" + docType + "-" + UUID.randomUUID() + "." + ext(contentType);
    }

    private static String ext(String contentType) {
        if (contentType == null) return "jpg";
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> "jpg";
        };
    }
}
