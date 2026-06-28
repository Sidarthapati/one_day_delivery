package com.oneday.hub.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// The system-generated packing list at seal (§7.3). Append-only and immutable (NFR-1, M7-D-008);
// a reschedule writes a NEW row superseding the prior via supersedes_id.
@Entity
@Table(name = "bag_manifest")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BagManifest {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    // References either flight_bag(id) (OUTBOUND) or delivery_bag(id) (INBOUND) — direction says which.
    @Column(name = "bag_id", nullable = false, updatable = false)
    private UUID bagId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private SortDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "manifest_kind", nullable = false, length = 16, updatable = false)
    private ManifestKind manifestKind;

    // Null for an INBOUND load-list (no flight); set for an OUTBOUND flight manifest.
    @Column(name = "flight_no", length = 20, updatable = false)
    private String flightNo;

    @Column(name = "parcel_count", nullable = false, updatable = false)
    private int parcelCount;

    @Column(name = "weight_grams", nullable = false, updatable = false)
    private int weightGrams;

    // JSON array of {parcelId, shipmentRef, destPincode, weightGrams}; the service (de)serializes via Jackson.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parcels", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String parcels;

    @Column(name = "supersedes_id", updatable = false)
    private UUID supersedesId;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @PrePersist
    void prePersist() {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }
}
