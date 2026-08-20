package com.oneday.auth.dto.response;

import java.time.Instant;

/** Returned by {@code POST /auth/refresh}: a fresh access JWT plus the rotated refresh token. */
public record TokenResponse(
        String token,
        Instant expiresAt,
        String refreshToken,
        Instant refreshExpiresAt
) {}
