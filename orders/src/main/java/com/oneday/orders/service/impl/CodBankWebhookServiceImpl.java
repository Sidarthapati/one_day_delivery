package com.oneday.orders.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.orders.config.CodWebhookProperties;
import com.oneday.orders.service.CodBankWebhookService;
import com.oneday.orders.service.CodCashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * @see CodBankWebhookService
 *
 * <p>Seam-stubbed until go-live (see the go-live issue): with no {@code cod.webhook.secret} set the
 * endpoint reports 503 and finance uses the manual confirm path. The exact RazorpayX inbound-credit
 * event shape is confirmed at go-live; this handler expects a minimal
 * {@code {"deposit_id": "...", "bank_credit_ref": "..."}} body — adapt the parse when the real event
 * lands.</p>
 */
@Service
class CodBankWebhookServiceImpl implements CodBankWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CodBankWebhookServiceImpl.class);

    private final CodWebhookProperties properties;
    private final CodCashService codCash;
    private final ObjectMapper objectMapper = new ObjectMapper();

    CodBankWebhookServiceImpl(CodWebhookProperties properties, CodCashService codCash) {
        this.properties = properties;
        this.codCash = codCash;
    }

    @Override
    public void confirmFromWebhook(String rawBody, String signature) {
        if (!properties.isEnabled()) {
            // No secret configured — the webhook path is not live yet (go-live issue). Finance confirms
            // manually via the admin endpoint until then.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Bank-credit webhook is not configured.");
        }
        String expected = WebhookSignatures.sign(rawBody, properties.getSecret());
        if (signature == null || !constantTimeEquals(expected, signature.trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
        }

        UUID depositId;
        String creditRef;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            depositId = UUID.fromString(root.path("deposit_id").asText());
            creditRef = root.path("bank_credit_ref").asText(null);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unparseable webhook payload.");
        }

        try {
            codCash.confirmBankCredit(depositId, creditRef, null);   // no city gate: provider call
        } catch (ResponseStatusException e) {
            // Already confirmed (409) is a duplicate delivery — providers retry — so treat it as success.
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                log.info("Bank-credit webhook: deposit {} already confirmed; treating as idempotent.", depositId);
                return;
            }
            throw e;
        }
    }

    /** Length-agnostic constant-time comparison, so a mismatch doesn't leak timing about the secret. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
