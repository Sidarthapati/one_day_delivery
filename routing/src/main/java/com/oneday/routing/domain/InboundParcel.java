package com.oneday.routing.domain;

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

// One accumulated inbound-parcel event awaiting binding. Written by the consumers, read by the ports.
@Entity
@Table(name = "inbound_parcel")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundParcel {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10, updatable = false)
    private InboundKind kind;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private UUID cityId;

    @Column(name = "da_id")
    private UUID daId;

    @Column(name = "destination_hex_id")
    private UUID destinationHexId;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Column(name = "valid_date", nullable = false, updatable = false)
    private LocalDate validDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = Instant.now();
    }
}
