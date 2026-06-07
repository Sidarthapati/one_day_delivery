package com.oneday.routing.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// A specific parcel bound to a van/loop/stop, one direction (M6-D-014). Mutable — status advances
// PLANNED → LOADED → ONBOARD → HANDED_OFF | RECONCILED | EXCEPTION through the custody points.
@Entity
@Table(name = "van_manifest_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VanManifestItem {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "manifest_id", nullable = false, updatable = false)
    private UUID manifestId;

    @Column(name = "parcel_id", nullable = false, updatable = false)
    private UUID parcelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private HandoffDirection direction;

    @Column(name = "stop_seq")
    private Integer stopSeq;

    @Column(name = "meeting_vertex_id")
    private UUID meetingVertexId;

    @Column(name = "counterparty_da_id")
    private UUID counterpartyDaId;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ManifestItemStatus status;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    @Column(name = "handed_off_at")
    private Instant handedOffAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = Instant.now();
    }
}
