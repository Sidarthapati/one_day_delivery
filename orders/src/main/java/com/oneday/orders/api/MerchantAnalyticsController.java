package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.MerchantAnalyticsResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.MerchantAnalyticsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Merchant self-service analytics. Owner-scoped like the CSV export ({@link B2bShipmentController}) —
 * the account is resolved from the caller, never taken as a parameter.
 */
@RestController
@RequestMapping("/api/v1/b2b/analytics")
class MerchantAnalyticsController {

    private final MerchantAnalyticsService analytics;
    private final B2bAccountRepository accounts;

    MerchantAnalyticsController(MerchantAnalyticsService analytics, B2bAccountRepository accounts) {
        this.analytics = analytics;
        this.accounts = accounts;
    }

    /**
     * @param days optional lookback window; a positive value restricts to the last N days, anything
     *             else (absent or ≤ 0) reports all-time.
     */
    @GetMapping
    public MerchantAnalyticsResponse myAnalytics(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(value = "days", required = false) Integer days) {
        UUID accountId = ownedAccountId(principal);
        Integer window = (days != null && days > 0) ? days : null;
        return analytics.forAccount(accountId, window);
    }

    /** The B2B account owned by the caller, or 404 (also gates the endpoint to B2B users). */
    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByMemberUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
