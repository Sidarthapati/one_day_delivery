package com.oneday.exceptions.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.exceptions.service.WhatsAppInboundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * @see WhatsAppInboundService
 *
 * <p><b>STUB (Discussion-2 viii, inbound half).</b> Verifies Meta's handshake + X-Hub-Signature-256 and
 * logs each inbound message; it does not yet attach a message to a support ticket (routing is a product
 * decision tracked as issue #182). Until a BSP/WABA account exists to test against, this is the
 * receiving seam, not a validated integration.
 */
@Service
class WhatsAppInboundServiceImpl implements WhatsAppInboundService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundServiceImpl.class);

    private final ObjectMapper mapper;
    /** Token echoed back on Meta's GET verify handshake. Same key the outbound config uses. */
    private final String verifyToken;
    /** App secret for X-Hub-Signature-256. When blank (no creds yet) we can't verify, so we reject. */
    private final String appSecret;

    WhatsAppInboundServiceImpl(ObjectMapper mapper,
                              @Value("${notify.whatsapp.verify-token:}") String verifyToken,
                              @Value("${notify.whatsapp.app-secret:}") String appSecret) {
        this.mapper = mapper;
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
    }

    @Override
    public boolean isValidHandshake(String mode, String token) {
        return "subscribe".equals(mode) && !verifyToken.isBlank() && verifyToken.equals(token);
    }

    @Override
    public boolean receive(String signatureHeader, byte[] rawBody) {
        // Reject anything we can't authenticate. No app secret configured → we can't verify → don't trust
        // it (the endpoint is public). A configured secret with a bad/missing signature is likewise rejected.
        if (appSecret.isBlank() || !signatureValid(signatureHeader, rawBody)) {
            log.warn("[whatsapp:inbound] rejected — unverifiable (app secret configured={}, signature present={})",
                    !appSecret.isBlank(), signatureHeader != null);
            return false;
        }
        try {
            logInboundMessages(mapper.readTree(rawBody));
        } catch (Exception e) {
            // Never make Meta retry on our parse error — accept and move on.
            log.warn("[whatsapp:inbound] could not parse payload: {}", e.toString());
        }
        return true;
    }

    /** Walk the Meta envelope (entry[].changes[].value.messages[]) and log sender + text length only. */
    private void logInboundMessages(JsonNode root) {
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode msg : change.path("value").path("messages")) {
                    String from = msg.path("from").asText(null);
                    int len = msg.path("text").path("body").asText("").length();
                    // No PII in plaintext — mask the number, log only the message length. TODO(#182):
                    // resolve `from` → open support ticket and append as a customer message.
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
