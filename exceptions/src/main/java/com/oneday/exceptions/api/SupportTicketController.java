package com.oneday.exceptions.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.exceptions.dto.PostMessageRequest;
import com.oneday.exceptions.dto.SupportTicketRequest;
import com.oneday.exceptions.dto.SupportTicketResponse;
import com.oneday.exceptions.dto.TicketActionRequest;
import com.oneday.exceptions.dto.TicketQueueResponse;
import com.oneday.exceptions.service.SupportTicketService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Support tickets + "call me" callbacks. Two audiences on one resource:
 * <ul>
 *   <li><b>Customers/merchants</b> raise and track their own tickets ({@code /mine}).</li>
 *   <li><b>Ops</b> (CALL_CENTER_AGENT + SUPERVISOR/STATION_MANAGER/ADMIN) work the queue and action them —
 *       the same console that handles {@link ExceptionController exception cases}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/support/tickets")
public class SupportTicketController {

    private final SupportTicketService service;

    public SupportTicketController(SupportTicketService service) {
        this.service = service;
    }

    // ── Customer / merchant ────────────────────────────────────────────────

    /** Raise a ticket or a callback request. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicketResponse create(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody SupportTicketRequest request) {
        Authz.requireCustomerRole(principal, Authz.B2C_CUSTOMER, Authz.C2C_CUSTOMER, Authz.B2B_USER);
        return service.create(UUID.fromString(Authz.requireUserId(principal)), Authz.role(principal), request);
    }

    /** The caller's own tickets, newest first. */
    @GetMapping("/mine")
    public List<SupportTicketResponse> mine(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireCustomerRole(principal, Authz.B2C_CUSTOMER, Authz.C2C_CUSTOMER, Authz.B2B_USER);
        return service.listMine(UUID.fromString(Authz.requireUserId(principal)));
    }

    /** One of the caller's own tickets, with its conversation thread. */
    @GetMapping("/mine/{id}")
    public SupportTicketResponse myDetail(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id) {
        Authz.requireCustomerRole(principal, Authz.B2C_CUSTOMER, Authz.C2C_CUSTOMER, Authz.B2B_USER);
        return service.myDetail(UUID.fromString(Authz.requireUserId(principal)), id);
    }

    /** Post a reply into the caller's own ticket (reopens it if it was resolved). */
    @PostMapping("/mine/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicketResponse replyMine(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody PostMessageRequest body) {
        Authz.requireCustomerRole(principal, Authz.B2C_CUSTOMER, Authz.C2C_CUSTOMER, Authz.B2B_USER);
        return service.postMineMessage(UUID.fromString(Authz.requireUserId(principal)), id, body.body());
    }

    // ── Ops console ────────────────────────────────────────────────────────

    /** The support queue: live tickets, freshest first. */
    @GetMapping
    public TicketQueueResponse queue(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Authz.requireRole(principal, Authz.CALL_CENTER_AGENT, Authz.SUPERVISOR, Authz.STATION_MANAGER);
        Page<SupportTicketResponse> p = service.queue(page, size);
        return new TicketQueueResponse(p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    /** One ticket for an ops agent, with its conversation thread. */
    @GetMapping("/{id}")
    public SupportTicketResponse detail(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id) {
        Authz.requireRole(principal, Authz.CALL_CENTER_AGENT, Authz.SUPERVISOR, Authz.STATION_MANAGER);
        return service.detail(id);
    }

    /** An ops agent posts a reply into a ticket (claims it if it was still OPEN). */
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicketResponse reply(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody PostMessageRequest body) {
        Authz.requireRole(principal, Authz.CALL_CENTER_AGENT, Authz.SUPERVISOR, Authz.STATION_MANAGER);
        return service.postAgentMessage(UUID.fromString(Authz.requireUserId(principal)),
                Authz.role(principal), id, body.body());
    }

    /** Action a ticket: claim (IN_PROGRESS), resolve, or cancel — with an optional note. */
    @PostMapping("/{id}/action")
    public SupportTicketResponse act(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody TicketActionRequest body) {
        Authz.requireRole(principal, Authz.CALL_CENTER_AGENT, Authz.SUPERVISOR, Authz.STATION_MANAGER);
        return service.act(id, Authz.requireUserId(principal), body.status(), body.note());
    }
}
