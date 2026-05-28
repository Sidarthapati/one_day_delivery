package com.oneday.auth.api;

import com.oneday.auth.dto.request.OnboardingSubmitRequest;
import com.oneday.auth.dto.request.RejectOnboardingRequest;
import com.oneday.auth.dto.response.OnboardingRequestResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping("/auth/request-onboarding")
    public ResponseEntity<OnboardingRequestResponse> submit(
            @Valid @RequestBody OnboardingSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(onboardingService.submit(request));
    }

    @GetMapping("/onboarding-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OnboardingRequestResponse>> listAll() {
        return ResponseEntity.ok(onboardingService.listAll());
    }

    @PostMapping("/onboarding-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal) {
        onboardingService.approve(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/onboarding-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> reject(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestBody(required = false) RejectOnboardingRequest request) {
        String reason = request != null ? request.reason() : null;
        onboardingService.reject(id, reason, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
