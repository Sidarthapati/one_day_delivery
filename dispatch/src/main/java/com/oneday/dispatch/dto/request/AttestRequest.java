package com.oneday.dispatch.dto.request;

import jakarta.validation.constraints.NotBlank;

/** A Play Integrity attestation: the server-issued {@code nonce} and the {@code token} the app got. */
public record AttestRequest(
        @NotBlank String nonce,
        @NotBlank String token) {
}
