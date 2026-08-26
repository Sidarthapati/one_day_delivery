package com.oneday.exceptions.dto;

import java.util.List;

/** A page of the ops support queue. */
public record TicketQueueResponse(
        List<SupportTicketResponse> tickets,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
