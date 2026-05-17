package com.oneday.auth.api;

import com.oneday.auth.dto.request.CreateRoleRequest;
import com.oneday.auth.dto.response.RoleResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoleResponse> createRole(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.ok(roleService.createRole(request, principal.getUserId()));
    }

    @GetMapping
    public ResponseEntity<List<RoleResponse>> listRoles() {
        return ResponseEntity.ok(roleService.listAllRoles());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivateRole(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthUserDetails principal) {
        roleService.deactivateRole(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
