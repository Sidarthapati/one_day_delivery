package com.oneday.routing.domain;

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

// Append-only (C17, M6-D-018). Per-stop, per-DA expected vs scanned reconciliation.
// discrepancyParcelIds is a JSON array of parcel UUID strings (the service layer (de)serializes).
@Entity
@Table(name = "handoff_reconciliation")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandoffReconciliation {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "manifest_id", nullable = false, updatable = false)
    private UUID manifestId;

    @Column(name = "stop_seq", nullable = false, updatable = false)
    private int stopSeq;

    @Column(name = "da_id", nullable = false, updatable = false)
    private UUID daId;

    @Column(name = "expected_count", nullable = false, updatable = false)
    private int expectedCount;

    @Column(name = "actual_count", nullable = false, updatable = false)
    private int actualCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 20, updatable = false)
    private DiscrepancyType discrepancyType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discrepancy_parcel_ids", columnDefinition = "jsonb", updatable = false)
    private String discrepancyParcelIds;

    @Column(name = "reason", columnDefinition = "text", updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
