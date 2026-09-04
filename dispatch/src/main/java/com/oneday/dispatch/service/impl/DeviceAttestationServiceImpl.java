package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.DeviceAttestationService;
import com.oneday.dispatch.service.PlayIntegrityVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nonce lifecycle for device attestation. Nonces are single-use, short-lived, and bound to the DA that
 * requested them, held in memory (they live seconds and never need to survive a restart). Verification
 * itself is delegated to {@link PlayIntegrityVerifier}; this class owns anti-replay: a nonce is
 * consumed on first {@link #verify} and can't be reused, and a token presented with an unknown/expired
 * nonce, or one issued to a different DA, is rejected before any crypto runs.
 */
@Service
class DeviceAttestationServiceImpl implements DeviceAttestationService {

    private static final int NONCE_BYTES = 32;

    private final PlayIntegrityVerifier verifier;
    private final DispatchProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    // nonce value → (daId, expiresAt). Consumed (removed) on verify.
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    DeviceAttestationServiceImpl(PlayIntegrityVerifier verifier, DispatchProperties props) {
        this.verifier = verifier;
        this.props = props;
    }

    @Override
    public Nonce issueNonce(UUID daId) {
        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plusSeconds(props.getAttestation().getNonceTtlSeconds());
        pending.put(value, new Pending(daId, expiresAt));
        sweepExpired();
        return new Nonce(value, expiresAt);
    }

    @Override
    public AttestationVerdict verify(UUID daId, String nonce, String integrityToken) {
        if (nonce == null || nonce.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing nonce");
        }
        Pending p = pending.remove(nonce); // single-use: consumed whether or not it verifies
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown or already-used nonce");
        }
        if (p.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Nonce expired");
        }
        if (!p.daId().equals(daId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nonce was issued to a different DA");
        }
        return verifier.verify(integrityToken, nonce);
    }

    private void sweepExpired() {
        Instant now = Instant.now();
        pending.values().removeIf(p -> p.expiresAt().isBefore(now));
    }

    private record Pending(UUID daId, Instant expiresAt) {}
}
