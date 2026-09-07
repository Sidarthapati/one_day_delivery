package com.oneday.orders.api;

import com.oneday.orders.service.CodBankWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound bank-credit webhook (Discussion-3 ix). Unauthenticated by design (permitted in SecurityConfig,
 * outside {@code /api/v1/**} so it skips the idempotency filter) — the HMAC signature is the auth. The
 * provider POSTs when a COD deposit's cash is credited to our account; the handler flips the deposit to
 * BANK_CONFIRMED and settles the DA's collections. Disabled (503) until a signing secret is configured.
 */
@RestController
@RequestMapping("/webhooks/cod")
class CodBankWebhookController {

    private final CodBankWebhookService webhook;

    CodBankWebhookController(CodBankWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping("/bank-credit")
    @ResponseStatus(HttpStatus.OK)
    public void bankCredit(
            @RequestBody String rawBody,
            @RequestHeader(name = "X-Godspeed-Signature", required = false) String signature) {
        webhook.confirmFromWebhook(rawBody, signature);
    }
}
