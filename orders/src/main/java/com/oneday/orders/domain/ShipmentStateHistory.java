package com.oneday.orders.domain;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.enums.TriggerSource;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_state_history")
@Getter
@Builder
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class ShipmentStateHistory {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", columnDefinition = "shipment_state", updatable = false)
    private ShipmentState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, columnDefinition = "shipment_state", updatable = false)
    private ShipmentState toState;

    @Column(name = "triggered_by", length = 100, nullable = false, updatable = false)
    private String triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", length = 20, nullable = false, updatable = false)
    private TriggerSource triggerSource;

    @Column(name = "event_ref", length = 200, updatable = false)
    private String eventRef;

    @Column(name = "notes", columnDefinition = "TEXT", updatable = false)
    private String notes;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    protected void prePersist() {
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }
}
