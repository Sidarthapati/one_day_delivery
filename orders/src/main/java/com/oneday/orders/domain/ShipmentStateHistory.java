package com.oneday.orders.domain;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.enums.TriggerSource;
import com.oneday.orders.service.TransitionContext;
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
import org.hibernate.annotations.ColumnTransformer;
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
    @ColumnTransformer(read = "from_state::text", write = "CAST(? AS shipment_state)")
    @Column(name = "from_state", columnDefinition = "shipment_state", updatable = false)
    private ShipmentState fromState;

    @Enumerated(EnumType.STRING)
    @ColumnTransformer(read = "to_state::text", write = "CAST(? AS shipment_state)")
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

    /**
     * Factory method used by the state machine to create a history row from a
     * completed transition. Maps all fields from {@link TransitionContext} directly.
     *
     * @param shipmentId the shipment that transitioned
     * @param fromState  the state before the transition (null only for the initial BOOKED entry)
     * @param toState    the state after the transition
     * @param ctx        transition metadata (who triggered it, source, event ref, notes)
     */
    public static ShipmentStateHistory of(UUID shipmentId,
                                          ShipmentState fromState,
                                          ShipmentState toState,
                                          TransitionContext ctx) {
        return ShipmentStateHistory.builder()
                .shipmentId(shipmentId)
                .fromState(fromState)
                .toState(toState)
                .triggeredBy(ctx.getTriggeredBy())
                .triggerSource(ctx.getTriggerSource())
                .eventRef(ctx.getEventRef())
                .notes(ctx.getNotes())
                .occurredAt(ctx.getOccurredAt())
                .build();
    }
}
