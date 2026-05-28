package com.oneday.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "role_audit_logs")
public class RoleAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // Stored as plain UUID — no FK so audit rows survive user deletion
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "target_user_id", nullable = false)
    private UUID targetUserId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "previous_role", length = 100)
    private String previousRole;

    @Column(name = "new_role", length = 100)
    private String newRole;

    @Column(name = "city_id", length = 50)
    private String cityId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @CreationTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
