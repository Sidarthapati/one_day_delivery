package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.dispatch.domain.AttendanceConfig;
import com.oneday.dispatch.dto.request.AttendanceConfigUpdateRequest;
import com.oneday.dispatch.dto.response.AttendanceConfigResponse;
import com.oneday.dispatch.service.AttendanceConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Global attendance config, ADMIN-only. v1 exposes the single {@code auto_present_enabled} switch that
 * gates geofence auto-present (manual "I've arrived" check-in is unaffected). Surfaced in the admin
 * console's Settings page.
 */
@RestController
public class AttendanceConfigController {

    private final AttendanceConfigService configService;

    public AttendanceConfigController(AttendanceConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/dispatch/admin/attendance-config")
    public AttendanceConfigResponse get(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.ADMIN);
        return toResponse(configService.get());
    }

    @PutMapping("/dispatch/admin/attendance-config")
    public AttendanceConfigResponse update(@RequestBody(required = false) AttendanceConfigUpdateRequest request,
                                           @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.ADMIN);
        UUID actor = Authz.requireUserId(principal);
        if (request == null || request.autoPresentEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auto_present_enabled is required");
        }
        return toResponse(configService.setAutoPresentEnabled(request.autoPresentEnabled(), actor));
    }

    private AttendanceConfigResponse toResponse(AttendanceConfig config) {
        return new AttendanceConfigResponse(
                config.isAutoPresentEnabled(), config.getUpdatedAt(), config.getUpdatedByUserId());
    }
}
