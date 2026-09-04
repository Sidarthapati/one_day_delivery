package com.oneday.dispatch.service;

import com.oneday.dispatch.service.DeviceAttestationService.AttestationVerdict;

/**
 * Verifies a Play Integrity token server-side. The real implementation decrypts + validates the token
 * against Google (device integrity, app integrity, licensing) and checks the embedded nonce. It's a
 * separate port so the crypto/Google dependency stays swappable and the nonce lifecycle is testable
 * without it. Ships with a config-gated stub; the production verifier that holds the Google key is a
 * follow-up (it needs {@code GOOGLE_PLAY_INTEGRITY_*} credentials, never committed).
 */
public interface PlayIntegrityVerifier {

    /** Verify {@code integrityToken}, confirming it embeds {@code expectedNonce}. */
    AttestationVerdict verify(String integrityToken, String expectedNonce);
}
