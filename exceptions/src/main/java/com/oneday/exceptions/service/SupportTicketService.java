package com.oneday.exceptions.service;

import com.oneday.exceptions.dto.SupportTicketRequest;
import com.oneday.exceptions.dto.SupportTicketResponse;
import com.oneday.exceptions.domain.TicketCategory;
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

    /** One of the caller's own tickets by id, with its conversation thread (404 if not theirs / unknown). */
    SupportTicketResponse myDetail(UUID raisedByUserId, UUID ticketId);

    /**
     * The raiser posts a reply into their own ticket's thread; a reply to a RESOLVED ticket reopens it.
     * Returns the updated ticket detail (with the full thread). 404 if the ticket isn't theirs.
     */
    SupportTicketResponse postMineMessage(UUID raisedByUserId, UUID ticketId, String body);

    /** Ops queue: live (unresolved) tickets, freshest first; optionally filtered to one category (null = all). */
    Page<SupportTicketResponse> queue(int page, int size, TicketCategory category);

    /** One ticket by id for an ops agent, with its conversation thread (404 if unknown). */
    SupportTicketResponse detail(UUID ticketId);

    /**
     * An ops agent posts a reply into a ticket's thread; replying to an OPEN ticket claims it (→ IN_PROGRESS,
     * assigned to the agent). Returns the updated ticket detail (with the full thread). 404 if unknown.
     */
    SupportTicketResponse postAgentMessage(UUID agentUserId, String agentRole, UUID ticketId, String body);

    /**
     * Ops action: set the ticket's status (IN_PROGRESS / RESOLVED / CANCELLED), record the acting
     * agent + note; terminal statuses stamp resolved_at.
     */
    SupportTicketResponse act(UUID ticketId, String agentUserId, TicketStatus status, String note);
}
