package com.oneday.assets.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The master record for one physical asset (M13). Mutable — {@code status} and the current-holder
 * pointer move over its life; the append-only {@link AssetCustodyEvent} ledger records every hop.
 * The reserved columns (metadata/quantity/financials) are unmapped in v1 — see V13_1.
 */
@Entity
@Table(name = "asset")
@Getter
@Setter
@NoArgsConstructor
public class Asset extends MutableBaseEntity {

    @Column(name = "asset_tag", length = 40, nullable = false)
    private String assetTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AssetCategory category;

    @Column(name = "asset_type", length = 40, nullable = false)
    private String assetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_mode", nullable = false, length = 16)
    private TrackingMode trackingMode = TrackingMode.SERIALIZED;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "make_model", length = 120)
    private String makeModel;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(name = "registration_number", length = 40)
    private String registrationNumber;

    @Column(name = "city_id", nullable = false)
    private UUID cityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AssetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssetCondition condition = AssetCondition.GOOD;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_holder_type", nullable = false, length = 16)
    private HolderType currentHolderType;

    @Column(name = "current_holder_id")
    private UUID currentHolderId;

    @Column(name = "current_holder_name", length = 160)
    private String currentHolderName;

    @Column(name = "held_since")
    private Instant heldSince;

    @Column(name = "ack_pending", nullable = false)
    private boolean ackPending;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "photo_keys")
    private List<String> photoKeys;

    @Column(nullable = false)
    private boolean active = true;
}
