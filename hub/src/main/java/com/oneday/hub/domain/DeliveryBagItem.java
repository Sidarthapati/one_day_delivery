package com.oneday.hub.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// The destination mirror of bag_item: "parcel X is in delivery bag Y, on stand Z" (§8.2). The
// delivery_bag unit + delivery_bag_id FK land in PR #2. Status-mutable.
@Entity
@Table(name = "delivery_bag_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryBagItem {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "shipment_ref", nullable = false, length = 30, updatable = false)
    private String shipmentRef;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "hub_id", nullable = false, updatable = false)
    private UUID hubId;

    @Column(name = "dest_hex_id")
    private UUID destHexId;

    @Column(name = "stand_id")
    private UUID standId;

    // The delivery bag this parcel was staged into (PR #2, M7-D-012) + the ladder outputs that picked it.
    @Column(name = "delivery_bag_id")
    private UUID deliveryBagId;

    @Column(name = "da_territory_id")
    private UUID daTerritoryId;

    @Column(name = "route_plan_id")
    private UUID routePlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "drop_type", nullable = false, length = 16, updatable = false)
    private com.oneday.common.domain.enums.DropType dropType;

    @Column(name = "loop_hint")
    private Integer loopHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryBagItemStatus status;

    @Column(name = "staged_at", nullable = false, updatable = false)
    private Instant stagedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (stagedAt == null) {
            stagedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
