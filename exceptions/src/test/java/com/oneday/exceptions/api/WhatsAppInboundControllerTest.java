package com.oneday.exceptions.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inbound WhatsApp webhook must satisfy Meta's two contracts: echo the challenge only on a matching
 * verify token, and (when an app secret is configured) accept a correctly-signed body while rejecting a
 * bad signature. It always 200s on a parseable POST so Meta doesn't retry-storm.
 */
class WhatsAppInboundControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String PAYLOAD = """
            {"entry":[{"changes":[{"value":{"messages":[
              {"from":"919000000009","text":{"body":"where is my parcel?"}}]}}]}]}""";

    @Test
    void verifyHandshakeEchoesChallengeOnMatchingToken() {
        var c = new WhatsAppInboundController(mapper, "the-token", "");
        ResponseEntity<String> r = c.verify("subscribe", "the-token", "CHALLENGE123");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo("CHALLENGE123");
    }

    @Test
    void verifyHandshakeRejectsWrongToken() {
        var c = new WhatsAppInboundController(mapper, "the-token", "");
        assertThat(c.verify("subscribe", "wrong", "CHALLENGE123").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void receiveRejectsWhenNoAppSecretConfigured() {
        // Public endpoint: with no secret we can't verify authenticity, so an unsigned POST is rejected
        // rather than trusted — nobody can push forged inbound messages until the account is connected.
        var c = new WhatsAppInboundController(mapper, "t", "");
        assertThat(c.receive(null, PAYLOAD.getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void receiveRejectsABadSignatureWhenConfigured() {
        var c = new WhatsAppInboundController(mapper, "t", "app-secret");
        var r = c.receive("sha256=deadbeef", PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void receiveAcceptsAValidSignature() throws Exception {
        String secret = "app-secret";
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

        var c = new WhatsAppInboundController(mapper, "t", secret);
        assertThat(c.receive(sig, body).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
