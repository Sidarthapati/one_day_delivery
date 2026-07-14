package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** The Google ID token (a JWT signed by Google) returned by Google Sign-In on the client. */
public record GoogleLoginRequest(
        @NotBlank String idToken
) {}
