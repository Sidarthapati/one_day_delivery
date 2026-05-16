package com.oneday.auth.api;

import com.oneday.auth.dto.response.PermissionCheckResponse;
import com.oneday.auth.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping("/check")
    public ResponseEntity<PermissionCheckResponse> check(
            @RequestParam UUID userId,
            @RequestParam String action,
            @RequestParam(required = false) String cityId) {
        return ResponseEntity.ok(permissionService.canDo(userId, action, cityId));
    }
}
