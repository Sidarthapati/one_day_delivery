package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.DeviceAttestationService.AttestationVerdict;
import com.oneday.dispatch.service.DeviceAttestationService.Nonce;
import com.oneday.dispatch.service.PlayIntegrityVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The nonce is anti-replay: single-use, DA-bound, expiring — and only then is the token verified. */
class DeviceAttestationServiceImplTest {

    private final PlayIntegrityVerifier verifier = mock(PlayIntegrityVerifier.class);
    private final DispatchProperties props = new DispatchProperties();
    private final DeviceAttestationServiceImpl service = new DeviceAttestationServiceImpl(verifier, props);

    private final UUID da = UUID.randomUUID();

    @Test
    void verify_consumesNonce_andDelegatesToVerifier() {
        when(verifier.verify(eq("tok"), any())).thenReturn(new AttestationVerdict(true, "PASS", "ok"));
        Nonce nonce = service.issueNonce(da);

        AttestationVerdict v = service.verify(da, nonce.value(), "tok");
        assertThat(v.passed()).isTrue();

        // Single-use: replaying the same nonce is now rejected.
        assertThatThrownBy(() -> service.verify(da, nonce.value(), "tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown or already-used nonce");
    }

    @Test
    void verify_rejectsNonceIssuedToAnotherDa() {
        Nonce nonce = service.issueNonce(da);
        assertThatThrownBy(() -> service.verify(UUID.randomUUID(), nonce.value(), "tok"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different DA");
    }

    @Test
    void verify_rejectsUnknownNonce() {
        assertThatThrownBy(() -> service.verify(da, "never-issued", "tok"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
