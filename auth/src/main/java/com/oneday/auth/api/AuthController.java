package com.oneday.auth.api;

import com.oneday.auth.dto.request.ApiKeyCreateRequest;
import com.oneday.auth.dto.request.GoogleLoginRequest;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.OtpRequestRequest;
import com.oneday.auth.dto.request.OtpVerifyRequest;
import com.oneday.auth.dto.request.RegisterRequest;
import com.oneday.auth.dto.response.ApiKeyCreateResponse;
import com.oneday.auth.dto.response.ApiKeyResponse;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.dto.response.OtpRequestResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    /** Sign in with a Google ID token. Verifies it, then mints the same session JWT as password login. */
    @PostMapping("/oauth/google")
    public ResponseEntity<LoginResponse> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return ResponseEntity.ok(authService.loginWithGoogle(request));
    }

    /** Request a one-time code by SMS. Response is identical whether or not the phone is registered. */
    @PostMapping("/otp/request")
    public ResponseEntity<OtpRequestResponse> requestOtp(@Valid @RequestBody OtpRequestRequest request) {
        return ResponseEntity.ok(authService.requestOtp(request));
    }

    /** Verify a phone OTP, exchanging it for a session JWT (creating the account on first use). */
    @PostMapping("/otp/verify")
    public ResponseEntity<LoginResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        return ResponseEntity.ok(authService.verifyOtp(request));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'B2B_USER', 'B2C_CUSTOMER')")
    @PostMapping("/api-keys")
    public ResponseEntity<ApiKeyCreateResponse> createApiKey(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody ApiKeyCreateRequest request) {
        return ResponseEntity.ok(authService.createApiKey(principal.getUserId(), request));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys(
            @AuthenticationPrincipal AuthUserDetails principal) {
        return ResponseEntity.ok(authService.listApiKeys(principal.getUserId()));
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable UUID keyId,
            @AuthenticationPrincipal AuthUserDetails principal) {
        authService.revokeApiKey(keyId, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
