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

// One parcel placed in a flight bag, with the weight it contributed (§14.3). Append-only: never
// deleted; a removal flips status to REMOVED and stamps removed_at.
@Entity
@Table(name = "flight_bag_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightBagItem {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bag_id", nullable = false, updatable = false)
    private UUID bagId;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "shipment_ref", nullable = false, length = 30, updatable = false)
    private String shipmentRef;

    @Column(name = "weight_grams", nullable = false, updatable = false)
    private int weightGrams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private FlightBagItemStatus status;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @PrePersist
    void prePersist() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }
}
