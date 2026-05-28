package com.oneday.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyCreateResponse(
        UUID id,
        String label,
        String rawKey,
        Instant createdAt
) {}
