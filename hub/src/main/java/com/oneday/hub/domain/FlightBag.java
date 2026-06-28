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

// The consolidation unit: one bag per (flight_no, flight_date, dest_hub) (§7.2). Status-mutable;
// contents append-only via bag_item.
@Entity
@Table(name = "flight_bag")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightBag {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "hub_id", nullable = false, updatable = false)
    private UUID hubId;

    @Column(name = "flight_no", nullable = false, length = 20, updatable = false)
    private String flightNo;

    @Column(name = "flight_date", nullable = false, updatable = false)
    private LocalDate flightDate;

    @Column(name = "origin_hub", nullable = false, length = 10, updatable = false)
    private String originHub;

    @Column(name = "dest_hub", nullable = false, length = 10, updatable = false)
    private String destHub;

    // The bag may move stands (overflow reassignment, M7-D-008); the pointer is mutable, history is in audit.
    @Column(name = "current_stand_id", nullable = false)
    private UUID currentStandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BagStatus status;

    @Column(name = "parcel_count", nullable = false)
    private int parcelCount;

    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "bag_cutoff")
    private Instant bagCutoff;

    @Column(name = "manifest_id")
    private UUID manifestId;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

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
