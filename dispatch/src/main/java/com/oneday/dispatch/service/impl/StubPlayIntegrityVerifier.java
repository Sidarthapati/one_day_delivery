package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.DeviceAttestationService.AttestationVerdict;
import com.oneday.dispatch.service.PlayIntegrityVerifier;
import org.springframework.stereotype.Component;

/**
 * Default, no-credential Play Integrity verifier. It cannot cryptographically verify a Google token
 * (that needs the project's decryption key), so it returns an UNKNOWN verdict that PASSES — the app's
 * own on-device checks are the real gate in dev. The production verifier (holding the Google key)
 * swaps in as {@code @Primary} once {@code dispatch.attestation.enabled=true}. When enabled here with
 * no real verifier present, it refuses to fake a PASS: it returns UNKNOWN/failed so a misconfiguration
 * is visible rather than silently trusting every token.
 */
@Component
class StubPlayIntegrityVerifier implements PlayIntegrityVerifier {

    private final DispatchProperties props;

    StubPlayIntegrityVerifier(DispatchProperties props) {
        this.props = props;
    }

    @Override
    public AttestationVerdict verify(String integrityToken, String expectedNonce) {
        if (integrityToken == null || integrityToken.isBlank()) {
            return new AttestationVerdict(false, "FAIL", "empty attestation token");
        }
        if (props.getAttestation().isEnabled()) {
            // Attestation is switched on but no real Google verifier is wired — don't fake a pass.
            return new AttestationVerdict(false, "UNKNOWN",
                    "attestation enabled but no Play Integrity verifier configured");
        }
        // Dev/default: the nonce round-trip is exercised, verification deferred to the real verifier.
        return new AttestationVerdict(true, "UNKNOWN", "stub verifier (attestation disabled)");
    }
}
