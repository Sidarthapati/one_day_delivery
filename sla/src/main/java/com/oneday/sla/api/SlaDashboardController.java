package com.oneday.sla.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.sla.dto.SlaActionRequest;
import com.oneday.sla.dto.SlaControlTowerResponse;
import com.oneday.sla.dto.SlaEscalationView;
import com.oneday.sla.dto.SlaPassRateResponse;
import com.oneday.sla.dto.SlaShipmentDetailResponse;
import com.oneday.sla.service.SlaQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The SLA control tower for STATION_MANAGER (own city) and ADMIN (all cities). Every endpoint is
 * role-gated and city-scoped like {@code AdminOrdersController}; action endpoints additionally require
 * the {@code sla:red:action} capability (held by STATION_MANAGER / SUPERVISOR / ADMIN).
 */
@RestController
@RequestMapping("/api/v1/sla")
public class SlaDashboardController {

    private final SlaQueryService service;

    public SlaDashboardController(SlaQueryService service) {
        this.service = service;
    }

    /** Live board of in-flight parcels; optional colour filter. */
    @GetMapping("/control-tower")
    public SlaControlTowerResponse controlTower(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(required = false) SlaState state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return service.controlTower(state, Authz.cityScope(principal), page, size);
    }

    /** Per-parcel leg breakdown + escalation history. */
    @GetMapping("/shipments/{ref}")
    public SlaShipmentDetailResponse shipment(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable String ref) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return service.detail(ref, Authz.cityScope(principal));
    }

    /** Open escalations awaiting action (RED / BREACHED). */
    @GetMapping("/red-queue")
    public List<SlaEscalationView> redQueue(@AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return service.redQueue(Authz.cityScope(principal));
    }

    /** Measured pass-rate over a window (default: last 24h). The 99% gate metric. */
    @GetMapping("/metrics/pass-rate")
    public SlaPassRateResponse passRate(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        return service.passRate(start, end, Authz.cityScope(principal));
    }

    /** Acknowledge an escalation (records who is on it). Requires sla:red:action. */
    @PostMapping("/escalations/{id}/ack")
    public ResponseEntity<Void> acknowledge(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id,
            @RequestBody(required = false) SlaActionRequest body) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        service.acknowledge(id, Authz.cityScope(principal), Authz.requireUserId(principal),
                Authz.role(principal), body != null ? body.notes() : null);
        return ResponseEntity.noContent().build();
    }

    /** Resolve an escalation (the acted-on outcome). Requires sla:red:action. */
    @PostMapping("/escalations/{id}/act")
    public ResponseEntity<Void> act(
            @AuthenticationPrincipal AuthUserDetails principal,
            @PathVariable UUID id,
            @RequestBody(required = false) SlaActionRequest body) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        service.resolve(id, Authz.cityScope(principal), Authz.requireUserId(principal),
                Authz.role(principal), body != null ? body.notes() : null);
        return ResponseEntity.noContent().build();
    }
}
