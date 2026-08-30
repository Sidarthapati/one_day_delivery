package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.TicketCategory;
import com.oneday.exceptions.domain.TicketChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Customer/merchant intake for a support ticket. Channel decides which fields matter:
 * TICKET needs a body; CALLBACK needs a contact phone. That cross-field rule is enforced in the
 * service (so the message is specific), not by bean validation here.
 */
public record SupportTicketRequest(
        @NotNull TicketChannel channel,
        TicketCategory category,                 // optional — what it's about; null = untagged
        @Size(max = 64) String shipmentRef,     // optional — the shipment this is about
        @Size(max = 200) String subject,
        @Size(max = 4000) String body,
        @Size(max = 20) String contactPhone) {  // required for CALLBACK
}
