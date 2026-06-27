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

// The dock record: one row per parcel taken into hub custody (§6, C12/C13). Append-only.
@Entity
@Table(name = "inbound_receipt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundReceipt {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "arrival_mode", nullable = false, length = 12, updatable = false)
    private ArrivalMode arrivalMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 12, updatable = false)
    private SortDirection direction;

    @Column(name = "reconciled", nullable = false, updatable = false)
    private boolean reconciled;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", length = 16, updatable = false)
    private DiscrepancyType discrepancyType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @PrePersist
    void prePersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }
}
