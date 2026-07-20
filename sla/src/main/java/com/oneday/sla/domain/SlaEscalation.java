package com.oneday.sla.domain;

import com.oneday.common.domain.enums.EscalationLevel;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
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

/**
 * Append-only record of one escalation raise (a leg going RED / breached). Never updated or deleted;
 * human responses are separate rows in {@code sla_action}. Standalone (no {@code updated_at}).
 */
@Entity
@Table(name = "sla_escalation")
@Getter
@Setter
@NoArgsConstructor
public class SlaEscalation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "shipment_ref")
    private String shipmentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "leg")
    private SlaLegType leg;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state")
    private SlaState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false)
    private SlaState toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false)
    private EscalationLevel level;

    @Column(name = "city")
    private String city;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "projected_finish_at")
    private Instant projectedFinishAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
