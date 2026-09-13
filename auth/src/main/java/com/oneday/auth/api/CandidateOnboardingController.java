package com.oneday.auth.api;

import com.oneday.auth.dto.request.AgreementAcceptRequest;
import com.oneday.auth.dto.request.CandidateBankRequest;
import com.oneday.auth.dto.request.CandidateLocationRequest;
import com.oneday.auth.dto.request.CandidatePersonalRequest;
import com.oneday.auth.dto.request.DocumentUploadUrlRequest;
import com.oneday.auth.dto.request.SubmitDocumentsRequest;
import com.oneday.auth.dto.response.CandidateResponse;
import com.oneday.auth.dto.response.DocumentResponse;
import com.oneday.auth.dto.response.DocumentUploadSlot;
import com.oneday.auth.service.DaOnboardingService;
import com.oneday.auth.service.OnboardingDocumentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public, no-login DA onboarding wizard. The opaque invite token is the capability (mirrors the
 * receiver delivery-accept page under {@code /public/**}, which SecurityConfig permits). The applicant
 * saves each step, then submits into the pre-approval queue.
 */
@RestController
@RequestMapping("/public/onboarding")
public class CandidateOnboardingController {

    private final DaOnboardingService onboardingService;
    private final OnboardingDocumentService documentService;

    public CandidateOnboardingController(DaOnboardingService onboardingService,
                                         OnboardingDocumentService documentService) {
        this.onboardingService = onboardingService;
        this.documentService = documentService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<CandidateResponse> get(@PathVariable String token) {
        return ResponseEntity.ok(onboardingService.getByToken(token));
    }

    @PutMapping("/{token}/personal")
    public ResponseEntity<CandidateResponse> personal(
            @PathVariable String token, @Valid @RequestBody CandidatePersonalRequest request) {
        return ResponseEntity.ok(onboardingService.updatePersonal(token, request));
    }

    @PutMapping("/{token}/location")
    public ResponseEntity<CandidateResponse> location(
            @PathVariable String token, @Valid @RequestBody CandidateLocationRequest request) {
        return ResponseEntity.ok(onboardingService.updateLocation(token, request));
    }

    @PutMapping("/{token}/bank")
    public ResponseEntity<CandidateResponse> bank(
            @PathVariable String token, @Valid @RequestBody CandidateBankRequest request) {
        return ResponseEntity.ok(onboardingService.updateBank(token, request));
    }

    // ── Documents (presign → PUT to R2 → submit keys) ─────────────────────────────

    @PostMapping("/{token}/documents/upload-urls")
    public ResponseEntity<List<DocumentUploadSlot>> uploadUrls(
            @PathVariable String token, @Valid @RequestBody DocumentUploadUrlRequest request) {
        return ResponseEntity.ok(documentService.presignUploads(token, request));
    }

    @PostMapping("/{token}/documents")
    public ResponseEntity<List<DocumentResponse>> submitDocuments(
            @PathVariable String token, @Valid @RequestBody SubmitDocumentsRequest request) {
        return ResponseEntity.ok(documentService.submitDocuments(token, request));
    }

    @GetMapping("/{token}/documents")
    public ResponseEntity<List<DocumentResponse>> documents(@PathVariable String token) {
        return ResponseEntity.ok(documentService.listByToken(token));
    }

    // ── Agreement + training (click-to-accept) ────────────────────────────────────

    @PostMapping("/{token}/agreement/accept")
    public ResponseEntity<CandidateResponse> acceptAgreement(
            @PathVariable String token,
            @RequestBody(required = false) AgreementAcceptRequest request) {
        String version = request != null ? request.version() : null;
        return ResponseEntity.ok(onboardingService.acceptAgreement(token, version));
    }

    @PostMapping("/{token}/training/ack")
    public ResponseEntity<CandidateResponse> acknowledgeTraining(@PathVariable String token) {
        return ResponseEntity.ok(onboardingService.acknowledgeTraining(token));
    }

    @PostMapping("/{token}/submit")
    public ResponseEntity<CandidateResponse> submit(@PathVariable String token) {
        return ResponseEntity.ok(onboardingService.submit(token));
    }
}
