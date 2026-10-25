package com.oneday.auth.service;

import com.oneday.auth.domain.DocumentStatus;
import com.oneday.auth.dto.request.DocumentUploadUrlRequest;
import com.oneday.auth.dto.request.SubmitDocumentsRequest;
import com.oneday.auth.dto.response.DocumentResponse;
import com.oneday.auth.dto.response.DocumentUploadSlot;

import java.util.List;
import java.util.UUID;

/** Document upload for the onboarding wizard — presign → PUT-to-R2 → submit keys → reviewer views. */
public interface OnboardingDocumentService {

    List<DocumentUploadSlot> presignUploads(String inviteToken, DocumentUploadUrlRequest request);

    List<DocumentResponse> submitDocuments(String inviteToken, SubmitDocumentsRequest request);

    List<DocumentResponse> listByToken(String inviteToken);

    List<DocumentResponse> listByCandidateId(UUID candidateId);

    DocumentResponse setStatus(UUID candidateId, UUID documentId, DocumentStatus status);
}
