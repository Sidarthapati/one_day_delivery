package com.oneday.sla.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One monitored leg of a shipment. Mutable — timestamps and colour advance as events land. */
@Entity
@Table(name = "sla_leg")
@Getter
@Setter
@NoArgsConstructor
public class SlaLeg extends MutableBaseEntity {

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leg", nullable = false)
    private SlaLegType leg;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "budget_minutes", nullable = false)
    private int budgetMinutes;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private SlaState state = SlaState.GREEN;

    /** Best live estimate of when this (open) leg will finish; null → use started_at + budget. */
    @Column(name = "projected_end_at")
    private Instant projectedEndAt;

    @Column(name = "source_event")
    private String sourceEvent;
}
