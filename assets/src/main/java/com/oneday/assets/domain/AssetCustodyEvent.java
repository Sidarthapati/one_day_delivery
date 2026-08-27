package com.oneday.assets.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row per asset custody exchange (M13). Append-only by construction: no setters, every
 * column {@code updatable = false}, and a DB trigger (V13_2) rejects UPDATE/DELETE. The asset's
 * current-holder pointer moves in the same transaction; this history is never edited. Mirrors
 * {@code ScanLedgerEntry}.
 */
@Entity
@Table(name = "asset_custody_event")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetCustodyEvent {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 28, updatable = false)
    private AssetEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_holder_type", length = 16, updatable = false)
    private HolderType fromHolderType;

    @Column(name = "from_holder_id", updatable = false)
    private UUID fromHolderId;

    @Column(name = "from_holder_name", length = 160, updatable = false)
    private String fromHolderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_holder_type", length = 16, updatable = false)
    private HolderType toHolderType;

    @Column(name = "to_holder_id", updatable = false)
    private UUID toHolderId;

    @Column(name = "to_holder_name", length = 160, updatable = false)
    private String toHolderName;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, updatable = false)
    private AssetCondition condition;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(columnDefinition = "text", updatable = false)
    private String reason;

    @Column(name = "evidence_url", columnDefinition = "text", updatable = false)
    private String evidenceUrl;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "client_event_id", length = 64, updatable = false)
    private String clientEventId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
