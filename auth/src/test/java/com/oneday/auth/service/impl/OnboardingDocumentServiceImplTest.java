package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.DaOnboardingDocument;
import com.oneday.auth.domain.DocumentStatus;
import com.oneday.auth.domain.OnboardingDocType;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.request.DocumentUploadUrlRequest;
import com.oneday.auth.dto.request.SubmitDocumentsRequest;
import com.oneday.auth.exception.OnboardingValidationException;
import com.oneday.auth.exception.StorageUnavailableException;
import com.oneday.auth.repository.DaOnboardingCandidateRepository;
import com.oneday.auth.repository.DaOnboardingDocumentRepository;
import com.oneday.common.port.ObjectStoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OnboardingDocumentServiceImplTest {

    private DaOnboardingCandidateRepository candidateRepo;
    private DaOnboardingDocumentRepository documentRepo;
    private ObjectStoragePort storage;
    private ObjectProvider<ObjectStoragePort> storageProvider;
    private OnboardingDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        candidateRepo = mock(DaOnboardingCandidateRepository.class);
        documentRepo = mock(DaOnboardingDocumentRepository.class);
        storage = mock(ObjectStoragePort.class);
        storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(storage.isAvailable()).thenReturn(true);
        when(documentRepo.save(any(DaOnboardingDocument.class))).thenAnswer(i -> i.getArgument(0));
        service = new OnboardingDocumentServiceImpl(candidateRepo, documentRepo, storageProvider);
    }

    @Test
    void presign_buildsNamespacedKeysAndUrls() {
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(draft()));
        when(storage.presignPut(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> "https://r2/put/" + i.getArgument(0));

        var slots = service.presignUploads("tok", new DocumentUploadUrlRequest(List.of(
                new DocumentUploadUrlRequest.Item(OnboardingDocType.AADHAAR_FRONT, null),
                new DocumentUploadUrlRequest.Item(OnboardingDocType.PAN, "application/pdf"))));

        assertThat(slots).hasSize(2);
        assertThat(slots.get(0).objectKey()).contains("onboarding/").contains("/AADHAAR_FRONT-").endsWith(".jpg");
        assertThat(slots.get(1).objectKey()).contains("/PAN-").endsWith(".pdf");
        assertThat(slots.get(0).uploadUrl()).startsWith("https://r2/put/");
    }

    @Test
    void presign_storageUnavailable_throws503() {
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(draft()));
        when(storageProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.presignUploads("tok",
                new DocumentUploadUrlRequest(List.of(
                        new DocumentUploadUrlRequest.Item(OnboardingDocType.PAN, null)))))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void presign_afterSubmit_rejected() {
        var c = draft();
        c.setStatus(OnboardingStatus.PENDING_APPROVAL);
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.presignUploads("tok",
                new DocumentUploadUrlRequest(List.of(
                        new DocumentUploadUrlRequest.Item(OnboardingDocType.PAN, null)))))
                .isInstanceOf(OnboardingValidationException.class);
    }

    @Test
    void submit_missingUpload_throws422() {
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(draft()));
        when(storage.exists("k-missing")).thenReturn(false);
        assertThatThrownBy(() -> service.submitDocuments("tok",
                new SubmitDocumentsRequest(List.of(
                        new SubmitDocumentsRequest.Item(OnboardingDocType.PAN, "k-missing")))))
                .isInstanceOf(OnboardingValidationException.class);
        verify(documentRepo, never()).save(any());
    }

    @Test
    void submit_persistsUploadedDocument() {
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(draft()));
        when(storage.exists("k-pan")).thenReturn(true);
        when(documentRepo.findByCandidateIdAndDocType(any(), eq(OnboardingDocType.PAN)))
                .thenReturn(Optional.empty());
        when(documentRepo.findByCandidateIdOrderByDocType(any())).thenReturn(List.of());

        service.submitDocuments("tok", new SubmitDocumentsRequest(List.of(
                new SubmitDocumentsRequest.Item(OnboardingDocType.PAN, "k-pan"))));

        ArgumentCaptor<DaOnboardingDocument> cap = ArgumentCaptor.forClass(DaOnboardingDocument.class);
        verify(documentRepo).save(cap.capture());
        assertThat(cap.getValue().getObjectKey()).isEqualTo("k-pan");
        assertThat(cap.getValue().getDocType()).isEqualTo(OnboardingDocType.PAN);
        assertThat(cap.getValue().getStatus()).isEqualTo(DocumentStatus.UPLOADED);
    }

    @Test
    void list_buildsPresignedDownloadUrls() {
        var doc = new DaOnboardingDocument();
        doc.setDocType(OnboardingDocType.PAN);
        doc.setObjectKey("k-pan");
        doc.setStatus(DocumentStatus.UPLOADED);
        when(documentRepo.findByCandidateIdOrderByDocType(any())).thenReturn(List.of(doc));
        when(storage.presignGet(eq("k-pan"), any(Duration.class))).thenReturn("https://r2/get/k-pan");

        var out = service.listByCandidateId(java.util.UUID.randomUUID());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).downloadUrl()).isEqualTo("https://r2/get/k-pan");
    }

    private static DaOnboardingCandidate draft() {
        var c = new DaOnboardingCandidate();
        c.setInviteToken("tok");
        c.setEmail("x@test.in");
        c.setStatus(OnboardingStatus.DRAFT);
        return c;
    }
}
