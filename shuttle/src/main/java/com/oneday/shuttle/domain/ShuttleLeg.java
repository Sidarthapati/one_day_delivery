package com.oneday.shuttle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-parcel binding of a parcel to the shuttle agent who took it on a leg — written the moment the
 * agent taps "Out to airport" / "Collected from airport". This is what lets tracking show the right
 * agent's GPS even when two agents are active. Append-only; latest row per parcel wins.
 */
@Entity
@Table(name = "shuttle_leg")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShuttleLeg {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private ShuttleDirection direction;

    @Column(name = "bag_id")
    private UUID bagId;

    @Column(name = "awb_id")
    private UUID awbId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void pre() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
