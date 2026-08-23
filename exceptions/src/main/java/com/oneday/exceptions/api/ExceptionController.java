package com.oneday.exceptions.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.dto.BatchResolveRequest;
import com.oneday.exceptions.dto.BatchResolveResponse;
import com.oneday.exceptions.dto.ExceptionCaseDetail;
import com.oneday.exceptions.dto.ExceptionQueueResponse;
import com.oneday.exceptions.dto.ExceptionSummaryResponse;
import com.oneday.exceptions.dto.ResolveRequest;
import com.oneday.exceptions.service.ExceptionCaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The M11 exceptions / problem-solve console for STATION_MANAGER (own city) and ADMIN (all cities).
 * Reads are role-gated + city-scoped like the SLA control tower; the resolve action additionally admits
 * the CALL_CENTER_AGENT.
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionController {

    private final ExceptionCaseService service;

    public ExceptionController(ExceptionCaseService service) {
        this.service = service;
    }

    /** The problem-solve queue: live cases, city-scoped, optional type filter, freshest first. */
    @GetMapping("/queue")
    public ExceptionQueueResponse queue(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(required = false) ExceptionType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return service.queue(Authz.cityScope(principal), type, page, size);
    }

    /** Disposition rollups over the live queue — the header cards. */
    @GetMapping("/summary")
    public ExceptionSummaryResponse summary(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return service.summary(Authz.cityScope(principal));
    }

    /** One case + its append-only action history. */
    @GetMapping("/{id}")
    public ExceptionCaseDetail detail(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return service.detail(id, Authz.cityScope(principal));
    }

    /** Take a problem-solve action (reschedule / RTO / resolve) — drives M4 + records the action. */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id,
            @Valid @RequestBody ResolveRequest body) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR, Authz.CALL_CENTER_AGENT);
        service.resolve(id, body.action(), Authz.cityScope(principal),
                Authz.requireUserId(principal), Authz.role(principal), body.notes());
        return ResponseEntity.noContent().build();
    }

    /** Apply one action to many cases (manage-packages batch ops). Returns a per-case outcome — a closed
     *  or missing case in the batch is reported, not fatal — so this is 200, not 204. */
    @PostMapping("/resolve")
    public BatchResolveResponse batchResolve(
            @AuthenticationPrincipal AuthUserDetails principal,
            @Valid @RequestBody BatchResolveRequest body) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR, Authz.CALL_CENTER_AGENT);
        return service.batchResolve(body, Authz.cityScope(principal),
                Authz.requireUserId(principal), Authz.role(principal));
    }
}
