package com.oneday.exceptions.domain;

import com.oneday.common.domain.MutableBaseEntity;
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

/**
 * A customer/merchant-initiated support request. Unlike {@link ExceptionCase} (opened by a failure
 * event, always bound to one shipment), a ticket is raised by a person, is shipment-optional, and
 * carries the reporter's identity + contact. Handled from the same ops console by the CALL_CENTER_AGENT.
 */
@Entity
@Table(name = "support_ticket")
@Getter
@Setter
@NoArgsConstructor
public class SupportTicket extends MutableBaseEntity {

    @Column(name = "raised_by_user_id", nullable = false, updatable = false)
    private UUID raisedByUserId;

    @Column(name = "raised_by_role", updatable = false)
    private String raisedByRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private TicketChannel channel;

    /** What the ticket is about (set at intake); nullable = untagged. Drives the ops-queue filter. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private TicketCategory category;

    /** Optional shipment this ticket is about (validated best-effort at intake). */
    @Column(name = "shipment_ref", updatable = false)
    private String shipmentRef;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body")
    private String body;

    /** For CALLBACK tickets — the number to call back. */
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TicketStatus status = TicketStatus.OPEN;

    /** The ops agent handling it (M1 user id). */
    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
