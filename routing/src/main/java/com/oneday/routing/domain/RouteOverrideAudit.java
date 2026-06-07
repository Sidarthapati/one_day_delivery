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

// Append-only (C17). Every approve/override/replan/fallback action with before/after snapshots.
@Entity
@Table(name = "route_override_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteOverrideAudit {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "route_plan_id", nullable = false, updatable = false)
    private UUID routePlanId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb", updatable = false)
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb", updatable = false)
    private String afterJson;

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
