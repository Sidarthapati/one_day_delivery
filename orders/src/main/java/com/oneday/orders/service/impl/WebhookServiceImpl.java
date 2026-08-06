package com.oneday.orders.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.domain.enums.CustomerType;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.WebhookDelivery;
import com.oneday.orders.domain.WebhookDeliveryStatus;
import com.oneday.orders.dto.WebhookConfigResponse;
import com.oneday.orders.dto.WebhookDeliveryResponse;
import com.oneday.orders.events.ShipmentTransitioned;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.WebhookDeliveryRepository;
import com.oneday.orders.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
class WebhookServiceImpl implements WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookServiceImpl.class);
    private static final String SIGNATURE_HEADER = "X-Godspeed-Signature";
    private static final String EVENT_HEADER = "X-Godspeed-Event";

    private final ShipmentRepository shipments;
    private final B2bAccountRepository accounts;
    private final WebhookDeliveryRepository deliveries;
    private final ObjectMapper objectMapper;
    private final SecureRandom rng = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "webhook-dispatch");
        t.setDaemon(true);
        return t;
    });

    WebhookServiceImpl(ShipmentRepository shipments, B2bAccountRepository accounts,
                       WebhookDeliveryRepository deliveries, ObjectMapper objectMapper) {
        this.shipments = shipments;
        this.accounts = accounts;
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
    }

    @Override
    public void dispatchForTransition(ShipmentTransitioned event) {
        Shipment shipment = shipments.findById(event.shipmentId()).orElse(null);
        if (shipment == null || shipment.getCustomerType() != CustomerType.B2B
                || shipment.getB2bAccountId() == null) {
            return;
        }
        B2bAccount account = accounts.findById(shipment.getB2bAccountId()).orElse(null);
        if (account == null || isBlank(account.getWebhookUrl())) {
            return;   // no webhook configured — nothing to do
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "shipment.state_changed");
        body.put("shipment_ref", event.shipmentRef());
        body.put("from_state", event.fromState() == null ? null : event.fromState().name());
        body.put("to_state", event.toState() == null ? null : event.toState().name());
        body.put("occurred_at", event.occurredAt() == null ? Instant.now().toString() : event.occurredAt().toString());
        body.put("account_id", account.getId().toString());

        final String url = account.getWebhookUrl();
        final String secret = account.getWebhookSecret();
        // Off the request/commit thread — a slow or dead endpoint must never affect the booking.
        executor.submit(() -> deliver(account.getId(), "shipment.state_changed", event.shipmentRef(), url, secret, body));
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookConfigResponse config(UUID accountId) {
        B2bAccount a = require(accountId);
        if (isBlank(a.getWebhookUrl())) return WebhookConfigResponse.none();
        return new WebhookConfigResponse(a.getWebhookUrl(), a.getWebhookSecret(), true);
    }

    @Override
    @Transactional
    public WebhookConfigResponse updateConfig(UUID accountId, String url, boolean regenerateSecret) {
        B2bAccount a = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        String trimmed = url == null ? null : url.trim();
        if (isBlank(trimmed)) {
            a.setWebhookUrl(null);   // clearing the URL disables webhooks (secret kept for re-enable)
        } else {
            if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook URL must be http(s)");
            }
            a.setWebhookUrl(trimmed);
            if (regenerateSecret || isBlank(a.getWebhookSecret())) {
                a.setWebhookSecret(generateSecret());
            }
        }
        accounts.save(a);
        return config(accountId);
    }

    @Override
    public WebhookDeliveryResponse sendTest(UUID accountId) {
        B2bAccount a = require(accountId);
        if (isBlank(a.getWebhookUrl())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No webhook URL configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "webhook.test");
        body.put("account_id", a.getId().toString());
        body.put("occurred_at", Instant.now().toString());
        body.put("message", "This is a test event from Godspeed for Business.");
        WebhookDelivery d = deliver(a.getId(), "webhook.test", null, a.getWebhookUrl(), a.getWebhookSecret(), body);
        return WebhookDeliveryResponse.from(d);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> recentDeliveries(UUID accountId, int limit) {
        return deliveries.findByB2bAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
                .stream().map(WebhookDeliveryResponse::from).toList();
    }

    // ── delivery ─────────────────────────────────────────────────────────────────

    private WebhookDelivery deliver(UUID accountId, String event, String shipmentRef,
                                    String url, String secret, Map<String, Object> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            json = "{}";
        }
        WebhookDelivery d = new WebhookDelivery();
        d.setB2bAccountId(accountId);
        d.setEvent(event);
        d.setShipmentRef(shipmentRef);
        d.setUrl(url);
        d.setPayload(json);
        d.setStatus(WebhookDeliveryStatus.PENDING);

        String signature = secret == null ? "" : WebhookSignatures.sign(json, secret);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE_HEADER, signature)
                    .header(EVENT_HEADER, event)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            d.setAttempts(1);
            d.setResponseCode(resp.statusCode());
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            d.setStatus(ok ? WebhookDeliveryStatus.DELIVERED : WebhookDeliveryStatus.FAILED);
            if (!ok) d.setError("HTTP " + resp.statusCode());
            log.info("Webhook {} → {} : {} ({})", event, url, resp.statusCode(), d.getStatus());
        } catch (Exception e) {
            d.setAttempts(1);
            d.setStatus(WebhookDeliveryStatus.FAILED);
            d.setError(truncate(e.getMessage(), 500));
            log.warn("Webhook {} → {} failed: {}", event, url, e.getMessage());
        }
        return deliveries.save(d);
    }

    private B2bAccount require(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
