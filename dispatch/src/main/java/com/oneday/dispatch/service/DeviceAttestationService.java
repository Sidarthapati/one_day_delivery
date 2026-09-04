package com.oneday.dispatch.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Device attestation (anti-abuse Phase 2). The app proves it's a genuine, unmodified build on an
 * un-tampered device via Google Play Integrity. Flow: the app asks the server for a single-use
 * {@link #issueNonce nonce}, requests a Play Integrity token bound to it, and sends the token back to
 * {@link #verify}; the server checks the nonce is one it issued (anti-replay) and hands the token to
 * the {@link com.oneday.dispatch.service.PlayIntegrityVerifier} for cryptographic verification. Per the
 * hybrid posture a failing verdict is surfaced/flagged, not silently trusted — the app hard-blocks on
 * its own device checks; this is the server-side corroboration.
 */
public interface DeviceAttestationService {

    /** Issue a single-use, short-lived nonce bound to this DA for a Play Integrity request. */
    Nonce issueNonce(UUID daId);

    /** Consume the nonce and verify the token. Throws if the nonce is unknown/expired/mismatched. */
    AttestationVerdict verify(UUID daId, String nonce, String integrityToken);

    record Nonce(String value, Instant expiresAt) {}

    /** @param verdict PASS / BASIC_ONLY / FAIL / UNKNOWN (verifier disabled or key absent). */
    record AttestationVerdict(boolean passed, String verdict, String detail) {}
}
