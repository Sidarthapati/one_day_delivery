package com.oneday.auth.api;

import com.oneday.auth.domain.DocumentStatus;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.auth.dto.request.RejectCandidateRequest;
import com.oneday.auth.dto.response.BgvCheckResponse;
import com.oneday.auth.dto.response.CandidateResponse;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.dto.response.DocumentResponse;
import com.oneday.auth.dto.response.OnboardingFunnelResponse;
import com.oneday.auth.dto.response.OnboardingInviteResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.BgvService;
import com.oneday.auth.service.DaOnboardingService;
import com.oneday.auth.service.OnboardingDocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Reviewer-facing DA onboarding funnel: open an invite, watch the pre-approval queue, and approve
 * (→ provision a real DA) or reject. The applicant's own wizard lives on the public
 * {@link CandidateOnboardingController} (token as the capability).
 */
@RestController
@RequestMapping("/das/onboarding")
public class DaOnboardingController {

    private final DaOnboardingService onboardingService;
    private final OnboardingDocumentService documentService;
    private final BgvService bgvService;

    public DaOnboardingController(DaOnboardingService onboardingService,
                                  OnboardingDocumentService documentService,
                                  BgvService bgvService) {
        this.onboardingService = onboardingService;
        this.documentService = documentService;
        this.bgvService = bgvService;
    }

    @PostMapping("/invite")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<OnboardingInviteResponse> invite(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody DaOnboardingInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(onboardingService.createInvite(request, principal.getUserId()));
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<List<CandidateResponse>> list(
            @RequestParam(required = false) OnboardingStatus status) {
        return ResponseEntity.ok(onboardingService.listCandidates(status));
    }

    @GetMapping("/funnel")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<OnboardingFunnelResponse> funnel() {
        return ResponseEntity.ok(onboardingService.funnel());
    }

    @PostMapping("/candidates/{id}/resend-invite")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<OnboardingInviteResponse> resendInvite(@PathVariable UUID id) {
        return ResponseEntity.ok(onboardingService.resendInvite(id));
    }

    @GetMapping("/candidates/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<CandidateResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(onboardingService.getCandidate(id));
    }

    @PostMapping("/candidates/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<DaResponse> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal) {
        return ResponseEntity.ok(onboardingService.approve(id, principal.getUserId()));
    }

    @PostMapping("/candidates/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<CandidateResponse> reject(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestBody(required = false) RejectCandidateRequest request) {
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(onboardingService.reject(id, reason, principal.getUserId()));
    }

    @GetMapping("/candidates/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<List<DocumentResponse>> documents(@PathVariable UUID id) {
        return ResponseEntity.ok(documentService.listByCandidateId(id));
    }

    @PostMapping("/candidates/{id}/documents/{docId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<DocumentResponse> setDocumentStatus(
            @PathVariable UUID id,
            @PathVariable UUID docId,
            @RequestParam DocumentStatus status) {
        return ResponseEntity.ok(documentService.setStatus(id, docId, status));
    }

    @GetMapping("/candidates/{id}/bgv")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<List<BgvCheckResponse>> bgv(@PathVariable UUID id) {
        return ResponseEntity.ok(bgvService.listByCandidate(id));
    }

    /** Force a poll of this candidate's in-flight checks now (else the poll job does it on its cadence). */
    @PostMapping("/candidates/{id}/bgv/refresh")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<List<BgvCheckResponse>> refreshBgv(@PathVariable UUID id) {
        return ResponseEntity.ok(bgvService.pollCandidate(id));
    }
}
