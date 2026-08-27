package com.oneday.common.kafka.events.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The payload M13 (assets) PRODUCES on {@code oneday.asset.events}
 * ({@link com.oneday.common.kafka.EventStreams#ASSET_EVENTS}) on every custody change. Routing key =
 * {@link #eventType} (the ledger event name: ISSUED, RETURNED, TRANSFERRED, REPORTED_LOST, …).
 *
 * <p>Emitted after commit. Future consumers: M11 (overdue / lost-asset inbox) and M6 (van registry) —
 * neither built yet. Kept as a plain record; may grow new nullable fields per the additive-evolution
 * rule without breaking consumers ({@code @JsonIgnoreProperties}).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssetCustodyChangedEvent(
        UUID eventId,
        String eventType,        // ledger event type name (assets domain AssetEventType)
        String schemaVersion,
        Instant occurredAt,
        UUID assetId,
        String assetTag,
        UUID cityId,
        String status,           // asset status after the change (IN_STOCK | ASSIGNED | ...)
        String fromHolderType,
        UUID fromHolderId,
        String toHolderType,
        UUID toHolderId,
        UUID actorId
) implements DomainEvent {

    public static final String SCHEMA_VERSION = "1.0";

    @Override
    public String partitionKey() {
        return assetId != null ? assetId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType;
    }
}
