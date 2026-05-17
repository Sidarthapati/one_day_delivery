package com.oneday.auth.api;

import com.oneday.auth.dto.request.ChangePasswordRequest;
import com.oneday.auth.dto.request.RegisterUserRequest;
import com.oneday.auth.dto.request.ResetPasswordRequest;
import com.oneday.auth.dto.request.RoleChangeRequest;
import com.oneday.auth.dto.request.UpdateProfileRequest;
import com.oneday.auth.dto.response.AuditLogResponse;
import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.AuthService;
import com.oneday.auth.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<UserResponse> createUser(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody RegisterUserRequest request) {
        return ResponseEntity.ok(userService.register(request, principal.getUserId()));
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<Void> changeRole(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody RoleChangeRequest request) {
        userService.changeRole(id, request, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/audit-log")
    public ResponseEntity<List<AuditLogResponse>> getAuditLog(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getAuditLog(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal) {
        userService.deactivate(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal) {
        userService.reactivate(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(id, request.newPassword(), principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.getUserId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me")
    public ResponseEntity<Void> updateProfile(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        userService.updateProfile(principal.getUserId(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUser(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CALL_CENTER_AGENT')")
    public ResponseEntity<UserResponse> getUserByEmail(@RequestParam String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }
}
