package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.SupportTicketMessage;

import java.time.Instant;
import java.util.UUID;

/** One message in a ticket's thread. {@code authorSide} is CUSTOMER or AGENT (derived from fromAgent). */
public record SupportTicketMessageResponse(
        UUID id,
        String authorSide,
        String authorRole,
        String body,
        Instant createdAt) {

    public static SupportTicketMessageResponse from(SupportTicketMessage m) {
        return new SupportTicketMessageResponse(
                m.getId(), m.isFromAgent() ? "AGENT" : "CUSTOMER", m.getAuthorRole(),
                m.getBody(), m.getCreatedAt());
    }
}
