package com.oneday.assets.dto;

import java.time.Instant;
import java.util.UUID;

/** One hop in an asset's chain of custody. */
public record AssetCustodyEventView(
        UUID id,
        String eventType,
        String fromHolderType,
        String fromHolderName,
        String toHolderType,
        String toHolderName,
        String condition,
        UUID actorId,
        String reason,
        Instant occurredAt,
        Instant recordedAt
) {}
