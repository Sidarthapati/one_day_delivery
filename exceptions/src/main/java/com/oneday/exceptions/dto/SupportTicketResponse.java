package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.SupportTicket;
import com.oneday.exceptions.domain.TicketChannel;
import com.oneday.exceptions.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/** Read model for a support ticket — same shape for the customer's own view and the ops console. */
public record SupportTicketResponse(
        UUID id,
        TicketChannel channel,
        String shipmentRef,
        String subject,
        String body,
        String contactPhone,
        TicketStatus status,
        String assignedTo,
        String resolutionNote,
        Instant createdAt,
        Instant resolvedAt) {

    public static SupportTicketResponse from(SupportTicket t) {
        return new SupportTicketResponse(
                t.getId(), t.getChannel(), t.getShipmentRef(), t.getSubject(), t.getBody(),
                t.getContactPhone(), t.getStatus(), t.getAssignedTo(), t.getResolutionNote(),
                t.getCreatedAt(), t.getResolvedAt());
    }
}
