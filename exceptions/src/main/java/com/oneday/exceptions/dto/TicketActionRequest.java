package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An ops agent's action on a ticket: move it to IN_PROGRESS (claim), RESOLVED, or CANCELLED, with an
 * optional note. RESOLVED/CANCELLED stamp {@code resolved_at} and close it out of the queue.
 */
public record TicketActionRequest(
        @NotNull TicketStatus status,
        @Size(max = 4000) String note) {
}
