package com.oneday.exceptions.service;

/**
 * The logic behind the inbound WhatsApp webhook — kept out of the controller (which only wires HTTP)
 * per the module package layout. Handles Meta's verify handshake and authenticates + processes an
 * inbound message payload. Routing a message to a support ticket is deferred (issue #182).
 */
public interface WhatsAppInboundService {

    /** True iff Meta's verify handshake is valid (mode=subscribe and the token matches the configured one). */
    boolean isValidHandshake(String mode, String token);

    /**
     * Authenticate and process an inbound payload. Returns {@code true} if accepted (a configured app
     * secret and a valid X-Hub-Signature-256), {@code false} if it must be rejected (unverifiable).
     */
    boolean receive(String signatureHeader, byte[] rawBody);
}
