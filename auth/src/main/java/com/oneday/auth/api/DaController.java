package com.oneday.auth.api;

import com.oneday.auth.dto.request.RegisterDaRequest;
import com.oneday.auth.dto.request.UpdateDaRequest;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.service.DaRegistrationService;
import com.oneday.common.domain.Shift;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin-facing DA management. A DA is a {@code DELIVERY_ASSOCIATE} user + an HR profile (contract +
 * shift); registering one here is what makes it appear in the shift roster ({@code DaDirectoryPort}).
 */
@RestController
@RequestMapping("/das")
public class DaController {

    private final DaRegistrationService daRegistrationService;

    public DaController(DaRegistrationService daRegistrationService) {
        this.daRegistrationService = daRegistrationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<DaResponse> register(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody RegisterDaRequest request) {
        return ResponseEntity.ok(daRegistrationService.register(request, principal.getUserId()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<List<DaResponse>> list(
            @RequestParam(required = false) String cityId,
            @RequestParam(required = false) Shift shift,
            @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(daRegistrationService.list(cityId, shift, active));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STATION_MANAGER')")
    public ResponseEntity<DaResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDaRequest request) {
        return ResponseEntity.ok(daRegistrationService.update(id, request));
    }
}
