package com.oneday.auth.api;

import com.oneday.auth.dto.response.PermissionCheckResponse;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.PermissionService;
import com.oneday.auth.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private static final Set<String> CROSS_USER_ROLES = Set.of("ADMIN", "CALL_CENTER_AGENT");

    private final PermissionService permissionService;
    private final UserService userService;

    public PermissionController(PermissionService permissionService, UserService userService) {
        this.permissionService = permissionService;
        this.userService = userService;
    }

    @GetMapping("/check")
    public ResponseEntity<PermissionCheckResponse> check(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String email,
            @RequestParam String action,
            @RequestParam(required = false) String cityId,
            @AuthenticationPrincipal AuthUserDetails principal) {

        if (userId == null && email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide either userId or email");
        }
        if (userId != null && email != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide either userId or email, not both");
        }

        if (email != null) {
            userId = userService.getUserByEmail(email).id();
        }

        boolean privileged = CROSS_USER_ROLES.contains(principal.getUser().getRole().getName());
        if (!privileged && !userId.equals(principal.getUserId())) {
            throw new ForbiddenException("You may only check your own permissions");
        }

        return ResponseEntity.ok(permissionService.canDo(userId, action, cityId));
    }
}
