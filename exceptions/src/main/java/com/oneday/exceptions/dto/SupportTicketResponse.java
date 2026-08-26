package com.oneday.exceptions.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.exceptions.domain.SupportTicket;
import com.oneday.exceptions.domain.TicketChannel;
import com.oneday.exceptions.domain.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a support ticket — same shape for the customer's own view and the ops console.
 * {@code messages} is populated only on a single-ticket detail view (omitted from list/queue rows).
 */
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
        Instant resolvedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<SupportTicketMessageResponse> messages) {

    /** List/queue row — no thread. */
    public static SupportTicketResponse from(SupportTicket t) {
        return build(t, null);
    }

    /** Detail view — with the full conversation thread. */
    public static SupportTicketResponse withThread(SupportTicket t, List<SupportTicketMessageResponse> messages) {
        return build(t, messages);
    }

    private static SupportTicketResponse build(SupportTicket t, List<SupportTicketMessageResponse> messages) {
        return new SupportTicketResponse(
                t.getId(), t.getChannel(), t.getShipmentRef(), t.getSubject(), t.getBody(),
                t.getContactPhone(), t.getStatus(), t.getAssignedTo(), t.getResolutionNote(),
                t.getCreatedAt(), t.getResolvedAt(), messages);
    }
}
