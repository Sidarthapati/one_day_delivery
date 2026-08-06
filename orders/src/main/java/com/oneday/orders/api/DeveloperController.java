package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.WebhookConfigRequest;
import com.oneday.orders.dto.WebhookConfigResponse;
import com.oneday.orders.dto.WebhookDeliveryResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The B2B developer surface's webhook configuration. API keys themselves are issued by M1
 * ({@code /auth/api-keys}); this controller owns the shipment-event webhook for the caller's account.
 */
@RestController
@RequestMapping("/api/v1/developer")
class DeveloperController {

    private final WebhookService webhooks;
    private final B2bAccountRepository accounts;

    DeveloperController(WebhookService webhooks, B2bAccountRepository accounts) {
        this.webhooks = webhooks;
        this.accounts = accounts;
    }

    @GetMapping("/webhook")
    public WebhookConfigResponse webhook(@AuthenticationPrincipal AuthUserDetails principal) {
        return webhooks.config(ownedAccountId(principal));
    }

    @PutMapping("/webhook")
    public WebhookConfigResponse saveWebhook(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody WebhookConfigRequest req) {
        return webhooks.updateConfig(ownedAccountId(principal), req.getUrl(), req.isRegenerateSecret());
    }

    @PostMapping("/webhook/test")
    public WebhookDeliveryResponse test(@AuthenticationPrincipal AuthUserDetails principal) {
        return webhooks.sendTest(ownedAccountId(principal));
    }

    @GetMapping("/webhook/deliveries")
    public List<WebhookDeliveryResponse> deliveries(@AuthenticationPrincipal AuthUserDetails principal) {
        return webhooks.recentDeliveries(ownedAccountId(principal), 25);
    }

    private UUID ownedAccountId(AuthUserDetails principal) {
        Authz.requireRole(principal, "B2B_USER");
        UUID userId = UUID.fromString(Authz.requireUserId(principal));
        return accounts.findByOwnerUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No B2B account for this user"))
                .getId();
    }
}
