package com.oneday.assets.events;

import com.oneday.assets.domain.AssetEventType;
import com.oneday.assets.domain.AssetStatus;
import com.oneday.assets.domain.HolderType;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process Spring event raised inside the custody transaction. {@link AssetEventProducer} listens
 * AFTER_COMMIT and publishes the broker event — so a rolled-back transaction never emits.
 */
public record AssetCustodyChanged(
        UUID eventId,
        AssetEventType eventType,
        Instant occurredAt,
        UUID assetId,
        String assetTag,
        UUID cityId,
        AssetStatus status,
        HolderType fromHolderType,
        UUID fromHolderId,
        HolderType toHolderType,
        UUID toHolderId,
        UUID actorId
) {}
