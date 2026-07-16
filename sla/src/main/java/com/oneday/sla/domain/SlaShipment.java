package com.oneday.sla.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.DeliveryType;
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

/** Per-shipment SLA rollup — the live control-tower row. Mutable (M10). */
@Entity
@Table(name = "sla_shipment")
@Getter
@Setter
@NoArgsConstructor
public class SlaShipment extends MutableBaseEntity {

    @Column(name = "shipment_id", nullable = false, unique = true)
    private UUID shipmentId;

    @Column(name = "shipment_ref")
    private String shipmentRef;

    @Column(name = "origin_city")
    private String originCity;

    @Column(name = "dest_city")
    private String destCity;

    @Column(name = "lane")
    private String lane;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type")
    private DeliveryType deliveryType;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    @Column(name = "internal_target_at", nullable = false)
    private Instant internalTargetAt;

    @Column(name = "public_promise_at", nullable = false)
    private Instant publicPromiseAt;

    @Column(name = "eta_promised")
    private Instant etaPromised;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_state", nullable = false)
    private SlaState overallState = SlaState.GREEN;

    @Column(name = "projected_finish_at")
    private Instant projectedFinishAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_leg")
    private SlaLegType currentLeg;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "breached", nullable = false)
    private boolean breached;
}
