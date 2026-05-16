package com.oneday.auth.api;

import com.oneday.auth.dto.request.ApiKeyCreateRequest;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.RegisterRequest;
import com.oneday.auth.dto.response.ApiKeyCreateResponse;
import com.oneday.auth.dto.response.ApiKeyResponse;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
