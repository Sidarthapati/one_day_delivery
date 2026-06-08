package com.oneday.routing.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RouteOverrideAudit;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanSource;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.dto.OverrideRequest;
import com.oneday.routing.dto.StopReassignment;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RouteOverrideAuditRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.RoutePlanLifecycleService;
import com.oneday.routing.service.RoutePlanningService;
import com.oneday.routing.service.ShuttleScheduleService;
import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.MeetingVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Nightly governance (§10). Approve flips PROPOSED→APPROVED and fans out the per-DA cron schedules
 * (DA_CRON_SCHEDULED), the publish event (ROUTE_PLAN_PUBLISHED) and the shuttle timetable. Override
 * never mutates a plan body: it clones the whole plan into a higher revision (source MANUAL_OVERRIDE,
 * {@code supersedes_plan_id} set), applies the vertex reassignments to the clone, supersedes the
 * original, records a {@code route_override_audit} row and emits ROUTE_CHANGED (C17, M6-D-008).
 */
@Service
class RoutePlanLifecycleServiceImpl implements RoutePlanLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanLifecycleServiceImpl.class);

    private final RoutePlanRepository routePlanRepository;
    private final RoutePlanStopRepository routePlanStopRepository;
    private final DaCronScheduleRepository daCronScheduleRepository;
    private final RouteOverrideAuditRepository auditRepository;
    private final RoutePlanningService routePlanningService;
    private final ShuttleScheduleService shuttleScheduleService;
    private final CronEventProducer cronEventProducer;
    private final GridDataAdapter gridDataAdapter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    RoutePlanLifecycleServiceImpl(RoutePlanRepository routePlanRepository,
                                  RoutePlanStopRepository routePlanStopRepository,
                                  DaCronScheduleRepository daCronScheduleRepository,
                                  RouteOverrideAuditRepository auditRepository,
                                  RoutePlanningService routePlanningService,
                                  ShuttleScheduleService shuttleScheduleService,
                                  CronEventProducer cronEventProducer,
                                  GridDataAdapter gridDataAdapter,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.routePlanRepository = routePlanRepository;
        this.routePlanStopRepository = routePlanStopRepository;
        this.daCronScheduleRepository = daCronScheduleRepository;
        this.auditRepository = auditRepository;
        this.routePlanningService = routePlanningService;
        this.shuttleScheduleService = shuttleScheduleService;
        this.cronEventProducer = cronEventProducer;
        this.gridDataAdapter = gridDataAdapter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RoutePlan approve(UUID planId, UUID actorId) {
        RoutePlan plan = routePlanRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No route_plan " + planId));
        if (plan.getStatus() != RoutePlanStatus.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plan " + planId + " is " + plan.getStatus() + ", only PROPOSED can be approved");
        }

        // Supersede any prior APPROVED plan for the same city/date (re-approval safety).
        for (RoutePlan other : routePlanRepository.findByCityIdAndValidForDate(plan.getCityId(), plan.getValidForDate())) {
            if (!other.getId().equals(planId) && other.getStatus() == RoutePlanStatus.APPROVED) {
                other.setStatus(RoutePlanStatus.SUPERSEDED);
                routePlanRepository.save(other);
            }
        }

        plan.setStatus(RoutePlanStatus.APPROVED);
        plan.setApprovedBy(actorId);
        plan.setApprovedAt(Instant.now(clock));
        routePlanRepository.save(plan);

        auditRepository.save(audit(plan.getId(), actorId, "APPROVE", null, plan, "Approved"));
        publishCronSchedules(plan);
        cronEventProducer.emitRoutePlanPublished(plan);
        publishShuttleQuietly(plan);

        log.info("Route plan {} approved for cityId={} date={} by {}",
                planId, plan.getCityId(), plan.getValidForDate(), actorId);
        return plan;
    }

    @Override
    @Transactional
    public RoutePlan override(UUID planId, OverrideRequest request) {
        RoutePlan source = routePlanRepository.findById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No route_plan " + planId));
        if (source.getStatus() != RoutePlanStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Plan " + planId + " is " + source.getStatus() + ", only an APPROVED plan can be overridden");
        }

        List<RoutePlanStop> sourceStops = routePlanStopRepository.findByRoutePlanId(planId);
        List<DaCronSchedule> sourceCrons = daCronScheduleRepository.findByRoutePlanId(planId);

        // stopId → new vertex; and old vertex → new vertex (to keep affected cron rows consistent).
        Map<UUID, UUID> stopReassign = new HashMap<>();
        Map<UUID, UUID> vertexReassign = new HashMap<>();
        for (StopReassignment r : request.reassignmentsOrEmpty()) {
            stopReassign.put(r.stopId(), r.newHexVertexId());
            sourceStops.stream().filter(s -> s.getId().equals(r.stopId())).findFirst()
                    .ifPresent(s -> vertexReassign.put(s.getHexVertexId(), r.newHexVertexId()));
        }

        UUID revisionId = UUID.randomUUID();
        RoutePlan revision = RoutePlan.builder()
                .id(revisionId)
                .cityId(source.getCityId())
                .validForDate(source.getValidForDate())
                .status(RoutePlanStatus.APPROVED)
                .source(RoutePlanSource.MANUAL_OVERRIDE)
                .solverType(source.getSolverType())
                .revision(source.getRevision() + 1)
                .supersedesPlanId(source.getId())
                .vansUsed(source.getVansUsed())
                .recommendedVanCount(source.getRecommendedVanCount())
                .provisioningFlag(source.getProvisioningFlag())
                .nLoops(source.getNLoops())
                .realisedCycleMinutes(source.getRealisedCycleMinutes())
                .notes("Override of plan " + source.getId()
                        + (request.reason() != null ? ": " + request.reason() : ""))
                .approvedBy(request.actorId())
                .approvedAt(Instant.now(clock))
                .build();

        List<RoutePlanStop> clonedStops = sourceStops.stream().map(s -> RoutePlanStop.builder()
                .routePlanId(revisionId)
                .vanId(s.getVanId())
                .loopIndex(s.getLoopIndex())
                .stopSeq(s.getStopSeq())
                .nodeKind(s.getNodeKind())
                .hexVertexId(stopReassign.getOrDefault(s.getId(), s.getHexVertexId()))
                .plannedArrival(s.getPlannedArrival())
                .plannedDeparture(s.getPlannedDeparture())
                .deliverQty(s.getDeliverQty())
                .collectQty(s.getCollectQty())
                .loadAfter(s.getLoadAfter())
                .build()).toList();

        List<DaCronSchedule> clonedCrons = sourceCrons.stream().map(c -> DaCronSchedule.builder()
                .routePlanId(revisionId)
                .daId(c.getDaId())
                .hexVertexId(vertexReassign.getOrDefault(c.getHexVertexId(), c.getHexVertexId()))
                .vanId(c.getVanId())
                .meetingTimes(c.getMeetingTimes())
                .cityId(c.getCityId())
                .validDate(c.getValidDate())
                .build()).toList();

        source.setStatus(RoutePlanStatus.SUPERSEDED);
        routePlanRepository.save(source);
        routePlanRepository.save(revision);
        if (!clonedStops.isEmpty()) routePlanStopRepository.saveAll(clonedStops);
        if (!clonedCrons.isEmpty()) daCronScheduleRepository.saveAll(clonedCrons);

        auditRepository.save(audit(revisionId, request.actorId(), "OVERRIDE", source, revision, request.reason()));

        // Affected DAs get a fresh cron schedule; M5/M10 react to the route change.
        publishCronSchedules(revision);
        cronEventProducer.emitRouteChanged(revision, request.actorId(), request.reason());

        log.info("Route plan {} overridden → revision {} ({} reassignments) by {}",
                planId, revisionId, stopReassign.size(), request.actorId());
        return revision;
    }

    @Override
    @Transactional
    public RoutePlan replan(UUID cityId, LocalDate date) {
        // Supersede the current live/proposed plans so the fresh PROPOSED becomes the active one.
        for (RoutePlan p : routePlanRepository.findByCityIdAndValidForDate(cityId, date)) {
            if (p.getStatus() == RoutePlanStatus.PROPOSED || p.getStatus() == RoutePlanStatus.APPROVED) {
                p.setStatus(RoutePlanStatus.SUPERSEDED);
                routePlanRepository.save(p);
            }
        }
        RoutePlan fresh = routePlanningService.plan(cityId, date);
        log.info("Forced replan for cityId={} date={} → plan {}", cityId, date, fresh.getId());
        return fresh;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoutePlan> activePlan(UUID cityId, LocalDate date) {
        return routePlanRepository.findByCityIdAndValidForDate(cityId, date).stream()
                .filter(p -> p.getStatus() == RoutePlanStatus.APPROVED || p.getStatus() == RoutePlanStatus.PROPOSED)
                // APPROVED beats PROPOSED; within a status the highest revision wins.
                .max(Comparator
                        .comparing((RoutePlan p) -> p.getStatus() == RoutePlanStatus.APPROVED)
                        .thenComparingInt(RoutePlan::getRevision));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoutePlanStop> stops(UUID planId, UUID vanId) {
        return routePlanStopRepository.findByRoutePlanId(planId).stream()
                .filter(s -> vanId == null || vanId.equals(s.getVanId()))
                .sorted(Comparator.comparingInt(RoutePlanStop::getLoopIndex)
                        .thenComparingInt(RoutePlanStop::getStopSeq))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaCronSchedule> cronForDa(UUID daId, LocalDate date) {
        // Many revisions can leave cron rows for the date; M5 wants only the live (APPROVED) schedule.
        return daCronScheduleRepository.findByDaIdAndValidDate(daId, date).stream()
                .filter(c -> routePlanRepository.findById(c.getRoutePlanId())
                        .map(p -> p.getStatus() == RoutePlanStatus.APPROVED)
                        .orElse(false))
                .toList();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void publishCronSchedules(RoutePlan plan) {
        Map<UUID, MeetingVertex> coords = vertexCoords(plan.getCityId(), plan.getValidForDate());
        for (DaCronSchedule cron : daCronScheduleRepository.findByRoutePlanId(plan.getId())) {
            MeetingVertex v = coords.get(cron.getHexVertexId());
            cronEventProducer.emitDaCronScheduled(cron,
                    v != null ? v.lat() : 0.0, v != null ? v.lon() : 0.0);
        }
    }

    // Resolve vertexId → coords for the city/date from M3 (best-effort; the event still carries the
    // vertex id, so a missing coord only blanks lat/lon).
    private Map<UUID, MeetingVertex> vertexCoords(UUID cityId, LocalDate date) {
        Map<UUID, MeetingVertex> map = new HashMap<>();
        try {
            for (DaTerritory t : gridDataAdapter.getDaTerritories(cityId, date)) {
                t.hexes().forEach(h -> h.vertices().forEach(v -> map.putIfAbsent(v.vertexId(), v)));
            }
        } catch (Exception e) {
            log.warn("Could not resolve vertex coords for cityId={} date={}: {}", cityId, date, e.getMessage());
        }
        return map;
    }

    private void publishShuttleQuietly(RoutePlan plan) {
        try {
            shuttleScheduleService.publish(plan.getCityId(), plan.getValidForDate(), plan.getId());
        } catch (Exception e) {
            log.warn("Shuttle publish failed for cityId={} date={}: {}",
                    plan.getCityId(), plan.getValidForDate(), e.getMessage());
        }
    }

    private RouteOverrideAudit audit(UUID planId, UUID actorId, String action,
                                     RoutePlan before, RoutePlan after, String reason) {
        return RouteOverrideAudit.builder()
                .routePlanId(planId)
                .actorId(actorId)
                .action(action)
                .beforeJson(summary(before))
                .afterJson(summary(after))
                .reason(reason)
                .build();
    }

    private String summary(RoutePlan p) {
        if (p == null) return null;
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "planId", p.getId().toString(),
                    "revision", p.getRevision(),
                    "status", p.getStatus().name(),
                    "vansUsed", p.getVansUsed() != null ? p.getVansUsed() : 0,
                    "nLoops", p.getNLoops() != null ? p.getNLoops() : 0));
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
