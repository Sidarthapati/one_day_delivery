package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.BrandingRequest;
import com.oneday.orders.dto.TrackBranding;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.BrandingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** A B2B merchant's white-label branding for their recipients' public tracking page. */
@RestController
@RequestMapping("/api/v1/branding")
class BrandingController {

    private final BrandingService branding;
    private final B2bAccountRepository accounts;

    BrandingController(BrandingService branding, B2bAccountRepository accounts) {
        this.branding = branding;
        this.accounts = accounts;
    }

    @GetMapping
    public TrackBranding get(@AuthenticationPrincipal AuthUserDetails principal) {
        return branding.get(ownedAccountId(principal));
    }

    @PutMapping
    public TrackBranding save(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody BrandingRequest request) {
        return branding.update(ownedAccountId(principal), request);
    }

    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByMemberUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
