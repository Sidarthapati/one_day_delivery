package com.oneday.exceptions.service;

import com.oneday.exceptions.dto.SupportTicketRequest;
import com.oneday.exceptions.dto.SupportTicketResponse;
import com.oneday.exceptions.domain.TicketStatus;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

/**
 * Customer/merchant support tickets + "call me" callbacks. Intake is self-scoped (the reporter is the
 * authenticated caller); the queue/detail/action side is the ops console (CALL_CENTER_AGENT et al.).
 */
public interface SupportTicketService {

    /** Raise a ticket as the given customer. Validates channel-specific fields and any shipment ref. */
    SupportTicketResponse create(UUID raisedByUserId, String raisedByRole, SupportTicketRequest request);

    /** The caller's own tickets, newest first. */
    List<SupportTicketResponse> listMine(UUID raisedByUserId);

    /** One of the caller's own tickets by id (404 if not theirs / unknown). */
    SupportTicketResponse myDetail(UUID raisedByUserId, UUID ticketId);

    /** Ops queue: live (unresolved) tickets, freshest first. */
    Page<SupportTicketResponse> queue(int page, int size);

    /** One ticket by id for an ops agent (404 if unknown). */
    SupportTicketResponse detail(UUID ticketId);

    /**
     * Ops action: set the ticket's status (IN_PROGRESS / RESOLVED / CANCELLED), record the acting
     * agent + note; terminal statuses stamp resolved_at.
     */
    SupportTicketResponse act(UUID ticketId, String agentUserId, TicketStatus status, String note);
}
