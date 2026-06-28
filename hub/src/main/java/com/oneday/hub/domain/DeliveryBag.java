package com.oneday.hub.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// The destination consolidation unit, mirror of FlightBag (§8.1, M7-D-012). One open bag per
// route/territory/zone key per day, sited on a stand from the shared pool. Status-mutable; contents
// append-only via delivery_bag_item.
@Entity
@Table(name = "delivery_bag")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryBag {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "hub_id", nullable = false, updatable = false)
    private UUID hubId;

    @Enumerated(EnumType.STRING)
    @Column(name = "bag_kind", nullable = false, length = 16, updatable = false)
    private BagKind bagKind;

    @Column(name = "bag_date", nullable = false, updatable = false)
    private LocalDate bagDate;

    // Exactly one of loop_id / da_territory_id / zone_id is set, per bag_kind (the lazy-create key).
    @Column(name = "route_plan_id", updatable = false)
    private UUID routePlanId;

    @Column(name = "loop_id", updatable = false)
    private UUID loopId;

    @Column(name = "da_territory_id", updatable = false)
    private UUID daTerritoryId;

    @Column(name = "zone_id", updatable = false)
    private UUID zoneId;

    // The bag may move stands (overflow reassignment, M7-D-008); pointer mutable, history in audit.
    @Column(name = "current_stand_id", nullable = false)
    private UUID currentStandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryBagStatus status;

    @Column(name = "parcel_count", nullable = false)
    private int parcelCount;

    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "manifest_id")
    private UUID manifestId;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
