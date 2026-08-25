package com.oneday.exceptions.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.DeliveryType;
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

/** One live problem-solve case for a shipment: what failed, why, how many attempts, and where it's headed. */
@Entity
@Table(name = "exception_case")
@Getter
@Setter
@NoArgsConstructor
public class ExceptionCase extends MutableBaseEntity {

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "shipment_ref")
    private String shipmentRef;

    // Order back-reference (Order → N shipments) — null for legacy/pre-order cases.
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_ref")
    private String orderRef;

    @Column(name = "origin_city")
    private String originCity;

    @Column(name = "dest_city")
    private String destCity;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type")
    private DeliveryType deliveryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ExceptionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false)
    private ExceptionReason reasonCode = ExceptionReason.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExceptionStatus status = ExceptionStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", nullable = false)
    private Disposition disposition = Disposition.REATTEMPTABLE;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Column(name = "da_attributable", nullable = false)
    private boolean daAttributable;

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "assigned_role")
    private String assignedRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution")
    private ResolveAction resolution;

    @Column(name = "notes")
    private String notes;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
