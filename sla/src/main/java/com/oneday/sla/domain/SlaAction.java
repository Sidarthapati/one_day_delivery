package com.oneday.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Append-only human action taken against an escalation (acknowledge / resolve / note). */
@Entity
@Table(name = "sla_action")
@Getter
@Setter
@NoArgsConstructor
public class SlaAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "escalation_id", nullable = false)
    private UUID escalationId;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private SlaActionType action;

    @Column(name = "acted_by", nullable = false)
    private String actedBy;

    @Column(name = "acted_by_role")
    private String actedByRole;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
