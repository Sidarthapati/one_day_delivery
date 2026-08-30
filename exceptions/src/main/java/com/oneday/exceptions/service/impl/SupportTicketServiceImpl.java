package com.oneday.exceptions.service.impl;

import com.oneday.exceptions.domain.SupportTicket;
import com.oneday.exceptions.domain.SupportTicketMessage;
import com.oneday.exceptions.domain.TicketCategory;
import com.oneday.exceptions.domain.TicketChannel;
import com.oneday.exceptions.domain.TicketStatus;
import com.oneday.exceptions.dto.SupportTicketMessageResponse;
import com.oneday.exceptions.dto.SupportTicketRequest;
import com.oneday.exceptions.dto.SupportTicketResponse;
import com.oneday.exceptions.repository.SupportTicketMessageRepository;
import com.oneday.exceptions.repository.SupportTicketRepository;
import com.oneday.exceptions.service.SupportTicketService;
import com.oneday.orders.service.ShipmentLookupService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class SupportTicketServiceImpl implements SupportTicketService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SupportTicketRepository repository;
    private final SupportTicketMessageRepository messages;
    private final ShipmentLookupService shipmentLookup;

    SupportTicketServiceImpl(SupportTicketRepository repository, SupportTicketMessageRepository messages,
                             ShipmentLookupService shipmentLookup) {
        this.repository = repository;
        this.messages = messages;
        this.shipmentLookup = shipmentLookup;
    }

    @Override
    @Transactional
    public SupportTicketResponse create(UUID raisedByUserId, String raisedByRole, SupportTicketRequest request) {
        String shipmentRef = trimToNull(request.shipmentRef());
        String contactPhone = trimToNull(request.contactPhone());
        String subject = trimToNull(request.subject());
        String body = trimToNull(request.body());

        // Channel-specific required fields — a specific 422 beats a generic bean-validation message.
        if (request.channel() == TicketChannel.CALLBACK) {
            if (contactPhone == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "contactPhone is required for a CALLBACK request");
            }
        } else if (body == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "body is required for a TICKET");
        }

        // Best-effort validation: if the reporter named a shipment, it must exist. (Ownership is not
        // enforced here — a ticket may legitimately be about an order the caller is chasing.)
        if (shipmentRef != null && shipmentLookup.findByRef(shipmentRef).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown shipment: " + shipmentRef);
        }

        SupportTicket t = new SupportTicket();
        t.setRaisedByUserId(raisedByUserId);
        t.setRaisedByRole(raisedByRole);
        t.setChannel(request.channel());
        t.setCategory(request.category());
        t.setShipmentRef(shipmentRef);
        t.setSubject(subject);
        t.setBody(body);
        t.setContactPhone(contactPhone);
        t.setStatus(TicketStatus.OPEN);
        return SupportTicketResponse.from(repository.save(t));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportTicketResponse> listMine(UUID raisedByUserId) {
        return repository.findByRaisedByUserIdOrderByCreatedAtDesc(raisedByUserId).stream()
                .map(SupportTicketResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SupportTicketResponse myDetail(UUID raisedByUserId, UUID ticketId) {
        SupportTicket t = repository.findByIdAndRaisedByUserId(ticketId, raisedByUserId)
                .orElseThrow(() -> notFound(ticketId));
        return withThread(t);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupportTicketResponse> queue(int page, int size, TicketCategory category) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Page<SupportTicket> p = category == null
                ? repository.findByResolvedAtIsNullOrderByCreatedAtDesc(pageable)
                : repository.findByResolvedAtIsNullAndCategoryOrderByCreatedAtDesc(category, pageable);
        return p.map(SupportTicketResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public SupportTicketResponse detail(UUID ticketId) {
        return withThread(repository.findById(ticketId).orElseThrow(() -> notFound(ticketId)));
    }

    @Override
    @Transactional
    public SupportTicketResponse postMineMessage(UUID raisedByUserId, UUID ticketId, String body) {
        SupportTicket t = repository.findByIdAndRaisedByUserId(ticketId, raisedByUserId)
                .orElseThrow(() -> notFound(ticketId));
        appendMessage(t, raisedByUserId, t.getRaisedByRole(), false, body);
        // A reply from the customer on a resolved ticket reopens it (the issue evidently isn't settled).
        if (t.getStatus() == TicketStatus.RESOLVED) {
            t.setStatus(TicketStatus.OPEN);
            t.setResolvedAt(null);
            repository.save(t);
        }
        return withThread(t);
    }

    @Override
    @Transactional
    public SupportTicketResponse postAgentMessage(UUID agentUserId, String agentRole, UUID ticketId, String body) {
        // Row-lock the ticket: the OPEN→IN_PROGRESS claim below must be atomic, or two agents replying at
        // once could both pass the check and clobber each other's assignment.
        SupportTicket t = repository.findByIdForUpdate(ticketId).orElseThrow(() -> notFound(ticketId));
        appendMessage(t, agentUserId, agentRole, true, body);
        // Replying to an untouched ticket claims it for this agent.
        if (t.getStatus() == TicketStatus.OPEN) {
            t.setStatus(TicketStatus.IN_PROGRESS);
            t.setAssignedTo(agentUserId.toString());
            repository.save(t);
        }
        return withThread(t);
    }

    @Override
    @Transactional
    public SupportTicketResponse act(UUID ticketId, String agentUserId, TicketStatus status, String note) {
        SupportTicket t = repository.findById(ticketId).orElseThrow(() -> notFound(ticketId));
        if (t.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ticket is already " + t.getStatus() + " and cannot be actioned");
        }
        if (status == TicketStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "OPEN is not a valid action; use IN_PROGRESS, RESOLVED, or CANCELLED");
        }
        t.setStatus(status);
        t.setAssignedTo(agentUserId);
        String trimmedNote = trimToNull(note);
        if (trimmedNote != null) {
            t.setResolutionNote(trimmedNote);
        }
        t.setResolvedAt(status.isTerminal() ? Instant.now() : null);
        return SupportTicketResponse.from(repository.save(t));
    }

    /** Load the ticket's thread (oldest first) and pack it into the detail response. */
    private SupportTicketResponse withThread(SupportTicket t) {
        List<SupportTicketMessageResponse> thread = messages.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                .map(SupportTicketMessageResponse::from)
                .toList();
        return SupportTicketResponse.withThread(t, thread);
    }

    private void appendMessage(SupportTicket t, UUID authorId, String authorRole, boolean fromAgent, String body) {
        String trimmed = trimToNull(body);
        if (trimmed == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Message body is required");
        }
        SupportTicketMessage m = new SupportTicketMessage();
        m.setTicketId(t.getId());
        m.setAuthorUserId(authorId);
        m.setAuthorRole(authorRole);
        m.setFromAgent(fromAgent);
        m.setBody(trimmed);
        messages.save(m);
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found: " + id);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
