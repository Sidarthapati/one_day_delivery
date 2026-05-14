package com.oneday.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        String label,
        boolean active,
        Instant lastUsedAt,
        Instant createdAt
) {}
