package com.oneday.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID actorId,
        UUID targetUserId,
        String action,
        String previousRole,
        String newRole,
        String cityId,
        String reason,
        Instant createdAt
) {}
