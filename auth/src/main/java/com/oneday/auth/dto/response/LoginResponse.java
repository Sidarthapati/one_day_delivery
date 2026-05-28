package com.oneday.auth.dto.response;

import java.time.Instant;

public record LoginResponse(
        String token,
        Instant expiresAt,
        String role,
        String cityId,
        boolean mustChangePassword
) {}
