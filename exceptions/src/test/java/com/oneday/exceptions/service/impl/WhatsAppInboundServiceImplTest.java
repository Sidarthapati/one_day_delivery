package com.oneday.exceptions.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inbound WhatsApp service must satisfy Meta's two contracts: validate the verify handshake only on
 * a matching token, and accept a POST only when it's authenticated — a configured app secret AND a valid
 * X-Hub-Signature-256. With no secret (stub), an unsigned body is rejected so nothing forged is trusted.
 */
class WhatsAppInboundServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] PAYLOAD = """
            {"entry":[{"changes":[{"value":{"messages":[
              {"from":"919000000009","text":{"body":"where is my parcel?"}}]}}]}]}"""
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void handshakeValidOnlyWithMatchingModeAndToken() {
        var s = new WhatsAppInboundServiceImpl(mapper, "the-token", "");
        assertThat(s.isValidHandshake("subscribe", "the-token")).isTrue();
        assertThat(s.isValidHandshake("subscribe", "wrong")).isFalse();
        assertThat(s.isValidHandshake("unsubscribe", "the-token")).isFalse();
    }

    @Test
    void receiveRejectsWhenNoAppSecretConfigured() {
        var s = new WhatsAppInboundServiceImpl(mapper, "t", ""); // stub mode — can't verify
        assertThat(s.receive(null, PAYLOAD)).isFalse();
    }

    @Test
    void receiveRejectsABadSignature() {
        var s = new WhatsAppInboundServiceImpl(mapper, "t", "app-secret");
        assertThat(s.receive("sha256=deadbeef", PAYLOAD)).isFalse();
    }

    @Test
    void receiveAcceptsAValidSignature() throws Exception {
        String secret = "app-secret";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(PAYLOAD));

        var s = new WhatsAppInboundServiceImpl(mapper, "t", secret);
        assertThat(s.receive(sig, PAYLOAD)).isTrue();
    }
}
