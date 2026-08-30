package com.oneday.exceptions.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Inbound WhatsApp webhook (Meta Cloud API) — the door a customer's WhatsApp reply arrives through.
 *
 * <p><b>STUB (Discussion-2 viii, inbound half).</b> This lands the two things Meta requires of any
 * webhook — the GET verify/challenge handshake and POST message receipt with X-Hub-Signature-256
 * verification — and <em>logs</em> each inbound message. It deliberately does <b>not</b> yet attach a
 * message to a support ticket: routing an inbound number to the right conversation is a product
 * decision tracked as a follow-up (issue #182). Until a BSP/WABA account exists to test against, this
 * is the receiving seam, not a validated integration.
 *
 * <p>Public (no JWT) because Meta calls it unauthenticated — authenticity comes from the signature, not
 * a bearer token. Because the path is public, an <b>unverifiable</b> POST is rejected: until an app
 * secret is configured (no BSP account yet) every POST is 403, so nobody can push forged inbound
 * messages. Once configured, a valid signature 200s and a bad one 403s.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
class WhatsAppInboundController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundController.class);

    private final ObjectMapper mapper;
    /** Token echoed back on Meta's GET verify handshake. Same key the outbound config uses. */
    private final String verifyToken;
    /** App secret for X-Hub-Signature-256. When blank (no creds yet) signature checking is skipped. */
    private final String appSecret;

    WhatsAppInboundController(ObjectMapper mapper,
                             @Value("${notify.whatsapp.verify-token:}") String verifyToken,
                             @Value("${notify.whatsapp.app-secret:}") String appSecret) {
        this.mapper = mapper;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
    }

    /** Meta verify handshake: echo the challenge iff the mode + token match. */
    @GetMapping
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                  @RequestParam(name = "hub.verify_token", required = false) String token,
                                  @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && !verifyToken.isBlank() && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        log.warn("[whatsapp:inbound] verify handshake rejected (mode={}, tokenMatch={})",
                mode, !verifyToken.isBlank() && verifyToken.equals(token));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** Receive inbound messages. Verifies the signature (when configured), logs each message, 200s. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                 @RequestBody byte[] rawBody) {
        // Reject anything we can't authenticate. No app secret configured → we can't verify → don't trust
        // it (the path is public). A configured secret with a bad/missing signature is likewise rejected.
        if (appSecret.isBlank() || !signatureValid(signature, rawBody)) {
            log.warn("[whatsapp:inbound] rejected — unverifiable (app secret configured={}, signature present={})",
                    !appSecret.isBlank(), signature != null);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            logInboundMessages(mapper.readTree(rawBody));
        } catch (Exception e) {
            // Never make Meta retry on our parse error — accept and move on.
            log.warn("[whatsapp:inbound] could not parse payload: {}", e.toString());
        }
        return ResponseEntity.ok().build();
    }

    /** Walk the Meta envelope (entry[].changes[].value.messages[]) and log sender + text. */
    private void logInboundMessages(JsonNode root) {
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode msg : change.path("value").path("messages")) {
                    String from = msg.path("from").asText(null);
                    int len = msg.path("text").path("body").asText("").length();
                    // Don't log PII in plaintext — mask the number, and log only the message length, not the
                    // body. TODO(#182): resolve `from` → open support ticket and append as a customer message.
                    log.info("[whatsapp:inbound] from={} chars={} — received (ticket routing deferred)",
                            maskPhone(from), len);
                }
            }
        }
    }

    /** Mask a phone number for logs — keep only the last 4 digits ("…6789"), never the whole number. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "…";
        }
        return "…" + phone.substring(phone.length() - 4);
    }

    /** Constant-time compare of the HMAC-SHA256 of the raw body against the "sha256=" header. */
    private boolean signatureValid(String header, byte[] rawBody) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    header.substring("sha256=".length()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
