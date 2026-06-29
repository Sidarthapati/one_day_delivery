package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.AssignmentDecision;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaAssignmentAudit;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.metrics.DispatchMetrics;
import com.oneday.dispatch.repository.DaAssignmentAuditRepository;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.AdjacentDaProvider;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.CronFeasibilityService;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.service.FeasibilityRequest;
import com.oneday.dispatch.service.FeasibilityResult;
import com.oneday.dispatch.service.FeasibilityStop;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.LatLon;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The assignment engine (design §6–§10). For an incoming task it resolves the tile, picks the
 * least-loaded assignable DA serving it, and — under that DA's exclusive lock — runs the
 * cheapest-insertion cron-feasibility check ({@link CronFeasibilityService}). On success it inserts a
 * {@code dispatch_queue} row at the cheapest position (bumping later rows) and mirrors the change into
 * the in-memory queue; on cron-infeasibility it tries cross-territory spill-over, then defers. Every
 * path writes one {@code da_assignment_audit} row.
 */
@Service
class DispatchServiceImpl implements DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchServiceImpl.class);
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS);

    private final DispatchQueueRepository queueRepository;
    private final DeferredDispatchRepository deferredRepository;
    private final DaAssignmentAuditRepository auditRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final CronFeasibilityService feasibilityService;
    private final IntradayLoadScoreService loadScoreService;
    private final AdjacentDaProvider adjacentDaProvider;
    private final GridService gridService;
    private final DaEventProducer daEventProducer;
    private final DispatchMetrics metrics;
    private final DispatchProperties props;

    DispatchServiceImpl(DispatchQueueRepository queueRepository,
                        DeferredDispatchRepository deferredRepository,
                        DaAssignmentAuditRepository auditRepository,
                        DaCronAssignmentRepository cronRepository,
                        DaStatusService daStatusService,
                        CronFeasibilityService feasibilityService,
                        IntradayLoadScoreService loadScoreService,
                        AdjacentDaProvider adjacentDaProvider,
                        GridService gridService,
                        DaEventProducer daEventProducer,
                        DispatchMetrics metrics,
                        DispatchProperties props) {
        this.queueRepository = queueRepository;
        this.deferredRepository = deferredRepository;
        this.auditRepository = auditRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.feasibilityService = feasibilityService;
        this.loadScoreService = loadScoreService;
        this.adjacentDaProvider = adjacentDaProvider;
        this.gridService = gridService;
        this.daEventProducer = daEventProducer;
        this.metrics = metrics;
        this.props = props;
    }

    @Override
    @Transactional
    public AssignmentResult assignPickup(UUID shipmentId, UUID cityId, double lat, double lon,
                                         UUID originTileId, String paymentMode) {
        return tracked(shipmentId, cityId,
                () -> assign(new Request(shipmentId, cityId, TaskType.PICKUP, lat, lon, originTileId, paymentMode), true));
    }

    @Override
    @Transactional
    public AssignmentResult assignDelivery(UUID shipmentId, UUID cityId, double lat, double lon, UUID destTileId) {
        return tracked(shipmentId, cityId,
                () -> assign(new Request(shipmentId, cityId, TaskType.DELIVERY, lat, lon, destTileId, null), true));
    }

    /** Wrap an assignment in MDC (shipment/city correlation) + record its outcome metric. */
    private AssignmentResult tracked(UUID shipmentId, UUID cityId, java.util.function.Supplier<AssignmentResult> work) {
        MDC.put("shipment_id", String.valueOf(shipmentId));
        MDC.put("city_id", String.valueOf(cityId));
        try {
            AssignmentResult result = work.get();
            metrics.assignment(result.outcome(), cityId);
            return result;
        } finally {
            MDC.remove("shipment_id");
            MDC.remove("city_id");
        }
    }

    @Override
    @Transactional
    public void cancelTask(UUID shipmentId, TaskType taskType) {
        Optional<DispatchQueue> active = queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, taskType);
        if (active.isEmpty()) {
            return;   // nothing to cancel (idempotent)
        }
        DispatchQueue row = active.get();
        if (row.getStatus() != TaskStatus.QUEUED && row.getStatus() != TaskStatus.IN_PROGRESS) {
            return;   // already terminal (COMPLETED/FAILED/CANCELLED/DEFERRED) — idempotent
        }
        // An IN_PROGRESS task means the DA has physically taken custody (parcel picked up / en route).
        // The shipment is now cancelled, so this is no longer a hub-bound pickup — it must leave the DA's
        // active load and cron budget (otherwise it starves feasibility for real parcels). The physical
        // parcel becomes a return (RTO); M11 owns that lane. We terminate the dispatch task either way and
        // log the custody hand-back so it's auditable.
        if (row.getStatus() == TaskStatus.IN_PROGRESS) {
            log.warn("Shipment {} cancelled while its {} task was IN_PROGRESS (DA {} holds the parcel) — "
                    + "removing from DA load; physical return is an RTO for M11",
                    shipmentId, taskType, row.getDaId());
        }
        UUID daId = row.getDaId();
        UUID cityId = row.getCityId();
        LocalDate date = row.getOperatingDate();
        daStatusService.withDaLock(daId, () -> {
            row.setStatus(TaskStatus.CANCELLED);
            queueRepository.save(row);
            resequence(daId, date);
            rebuildMemQueue(daId, date);
            return null;
        });
        // Queue order changed → notify downstream (gated; no-op until the producer flag is on).
        daEventProducer.emitQueueReordered(daId, cityId);
    }

    @Override
    @Transactional
    public AssignmentResult reassignDeferred(UUID deferredId) {
        DeferredDispatch deferred = deferredRepository.findById(deferredId)
                .orElseThrow(() -> new IllegalArgumentException("No deferred dispatch " + deferredId));
        if (!"PENDING".equals(deferred.getStatus())) {
            return AssignmentResult.deferred(deferredId, deferred.getDeferReason());
        }
        // paymentMode is not carried on the deferred row → null on retry (COD prioritisation is later).
        AssignmentResult result = assign(new Request(deferred.getShipmentId(), deferred.getCityId(),
                deferred.getTaskType(), deferred.getTaskLat(), deferred.getTaskLon(),
                deferred.getTileId(), null), false);
        if (result.outcome() != com.oneday.dispatch.service.AssignmentOutcome.DEFERRED) {
            deferred.setStatus("ASSIGNED");
            deferred.setAssignedAt(Instant.now());
            deferredRepository.save(deferred);
        }
        metrics.assignment(result.outcome(), deferred.getCityId());
        return result;
    }

    // ── core ─────────────────────────────────────────────────────────────────────────────────────

    private AssignmentResult assign(Request req, boolean createDeferral) {
        LocalDate date = today();
        UUID tileId = req.tileId() != null ? req.tileId() : resolveTile(req.cityId(), req.lat(), req.lon());

        List<UUID> roster = daStatusService.dasForTile(tileId);
        List<UUID> assignable = roster.stream().filter(this::isAssignable).toList();

        if (assignable.isEmpty()) {
            boolean frozenPresent = roster.stream().anyMatch(this::isCronFrozen);
            DeferReason reason = frozenPresent ? DeferReason.CRON_LOCKED : DeferReason.NO_DA_AVAILABLE;
            AssignmentDecision decision = frozenPresent
                    ? AssignmentDecision.DEFERRED_FROZEN : AssignmentDecision.DEFERRED_NO_DA;
            Instant retryAfter = frozenPresent ? earliestFrozenRetry(roster, date) : null;
            return defer(req, tileId, date, reason, decision, retryAfter, createDeferral);
        }

        UUID primary = assignable.stream()
                .min(Comparator.comparingInt(this::queueDepth))
                .orElseThrow();

        Optional<AssignmentResult> assigned = daStatusService.withDaLock(primary,
                () -> attemptOnDa(primary, req, tileId, date, false));
        if (assigned.isPresent()) {
            return assigned.get();
        }

        // Primary is cron-infeasible at every position → try cross-territory before deferring.
        Optional<AssignmentResult> spill = tryCrossTerritory(req, tileId, date, primary);
        if (spill.isPresent()) {
            return spill.get();
        }
        return defer(req, tileId, date, DeferReason.CRON_INFEASIBLE,
                AssignmentDecision.DEFERRED_CRON, null, createDeferral);
    }

    /**
     * Try to place the task on {@code daId}. Returns the assignment, or empty when the DA has an
     * active cron the task cannot make at any insertion position (no DB writes on empty).
     */
    private Optional<AssignmentResult> attemptOnDa(UUID daId, Request req, UUID tileId,
                                                   LocalDate date, boolean crossTerritory) {
        List<DispatchQueue> activeRows = sortedActive(daId, date);
        List<DispatchQueue> inProgress = activeRows.stream()
                .filter(r -> r.getStatus() == TaskStatus.IN_PROGRESS).toList();
        List<DispatchQueue> queued = activeRows.stream()
                .filter(r -> r.getStatus() == TaskStatus.QUEUED).toList();

        DaCronAssignment cron = cronRepository.findByDaIdAndOperatingDate(daId, date).orElse(null);
        boolean cronActive = cron != null && cron.getScheduledMeetingTime() != null
                && cron.getStatus() != CronAssignmentStatus.COMPLETED
                && cron.getStatus() != CronAssignmentStatus.CANCELLED
                && cron.getStatus() != CronAssignmentStatus.MISSED;

        FeasibilityResult feasibility = null;
        int queuedInsertIndex;
        if (cronActive) {
            feasibility = feasibilityService.checkFeasibility(buildFeasibility(daId, inProgress, queued, req, cron));
            if (!feasibility.feasible()) {
                return Optional.empty();
            }
            queuedInsertIndex = feasibility.bestInsertionIndex();
        } else {
            queuedInsertIndex = queued.size();   // no cron constraint → append
        }

        int absolutePosition = inProgress.size() + queuedInsertIndex;
        for (DispatchQueue r : activeRows) {
            if (r.getQueuePosition() >= absolutePosition) {
                r.setQueuePosition(r.getQueuePosition() + 1);
            }
        }
        queueRepository.saveAll(activeRows);
        queueRepository.save(newRow(daId, req, tileId, date, absolutePosition, crossTerritory, cronActive));
        rebuildMemQueue(daId, date);

        AssignmentDecision decision = crossTerritory
                ? AssignmentDecision.CROSS_TERRITORY_ASSIGNED : AssignmentDecision.ASSIGNED;
        writeAudit(req, tileId, daId, decision, queuedInsertIndex, feasibility);
        log.debug("Assigned {} of shipment {} to DA {} at position {} (cross={})",
                req.taskType(), req.shipmentId(), daId, absolutePosition, crossTerritory);

        return Optional.of(crossTerritory
                ? AssignmentResult.crossTerritory(daId, absolutePosition)
                : AssignmentResult.assigned(daId, absolutePosition));
    }

    private Optional<AssignmentResult> tryCrossTerritory(Request req, UUID originTileId,
                                                         LocalDate date, UUID primaryDa) {
        DispatchProperties.CrossTerritory cfg = props.getCrossTerritory();
        if (!cfg.isEnabled()) {
            return Optional.empty();
        }
        // Cross-territory leans on M3 load scores (+ an adjacency source). Treat any failure there as
        // a degraded-but-safe signal: skip the spill-over and let the caller defer normally.
        try {
            if (loadScore(originTileId, date) < cfg.getOverloadThreshold()) {
                return Optional.empty();   // origin not overloaded enough to spill
            }
            for (AdjacentDaProvider.Candidate cand : adjacentDaProvider.candidates(req.cityId(), originTileId, date)) {
                if (cand.daId().equals(primaryDa) || !isAssignable(cand.daId())) {
                    continue;
                }
                if (loadScore(cand.tileId(), date) >= cfg.getSparseThreshold()) {
                    continue;   // neighbour not sparse enough to receive
                }
                Optional<AssignmentResult> r = daStatusService.withDaLock(cand.daId(),
                        () -> attemptOnDa(cand.daId(), req, originTileId, date, true));
                if (r.isPresent()) {
                    return r;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Cross-territory check failed for shipment {} — skipping spill-over: {}",
                    req.shipmentId(), e.getMessage());
        }
        return Optional.empty();
    }

    private AssignmentResult defer(Request req, UUID tileId, LocalDate date, DeferReason reason,
                                   AssignmentDecision decision, Instant retryAfter, boolean createRow) {
        UUID deferredId = null;
        if (createRow) {
            DeferredDispatch d = new DeferredDispatch();
            d.setCityId(req.cityId());
            d.setShipmentId(req.shipmentId());
            d.setTaskType(req.taskType());
            d.setTileId(tileId);
            d.setTaskLat(req.lat());
            d.setTaskLon(req.lon());
            d.setDeferReason(reason);
            d.setRetryAfter(retryAfter);
            d.setStatus("PENDING");
            d.setOperatingDate(date);
            deferredId = deferredRepository.save(d).getId();
        }
        writeAudit(req, tileId, null, decision, null, null);
        log.debug("Deferred {} of shipment {} ({})", req.taskType(), req.shipmentId(), reason);
        return AssignmentResult.deferred(deferredId, reason);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private FeasibilityRequest buildFeasibility(UUID daId, List<DispatchQueue> inProgress,
                                                List<DispatchQueue> queued, Request req, DaCronAssignment cron) {
        long serviceSeconds = props.getService().getDefaultMinutes() * 60L;
        LatLon current;
        Instant currentTime;
        if (!inProgress.isEmpty()) {
            // §8.4: anchor on the in-progress stop and its expected completion (conservative).
            DispatchQueue anchor = inProgress.stream()
                    .max(Comparator.comparing(r -> r.getExpectedEta() != null ? r.getExpectedEta() : Instant.now()))
                    .orElseThrow();
            current = new LatLon(anchor.getTaskLat(), anchor.getTaskLon());
            currentTime = anchor.getExpectedEta() != null ? anchor.getExpectedEta() : Instant.now();
        } else {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            current = new LatLon(live.getLat(), live.getLon());
            currentTime = Instant.now();
        }
        List<FeasibilityStop> existing = queued.stream()
                .map(r -> new FeasibilityStop(new LatLon(r.getTaskLat(), r.getTaskLon()), serviceSeconds))
                .toList();
        FeasibilityStop newTask = new FeasibilityStop(new LatLon(req.lat(), req.lon()), serviceSeconds);
        LatLon cronVertex = new LatLon(cron.getMeetingLat(), cron.getMeetingLon());
        return new FeasibilityRequest(current, currentTime, existing, newTask, cronVertex,
                cron.getScheduledMeetingTime());
    }

    private DispatchQueue newRow(UUID daId, Request req, UUID tileId, LocalDate date,
                                 int position, boolean crossTerritory, boolean cronSafe) {
        DispatchQueue row = new DispatchQueue();
        row.setDaId(daId);
        row.setCityId(req.cityId());
        row.setShipmentId(req.shipmentId());
        row.setTaskType(req.taskType());
        row.setTaskLat(req.lat());
        row.setTaskLon(req.lon());
        row.setTileId(tileId);
        row.setHomeTileId(tileId);
        row.setQueuePosition(position);
        row.setStatus(TaskStatus.QUEUED);
        row.setPaymentMode(req.paymentMode());
        row.setCrossTerritory(crossTerritory);
        row.setCronSafe(cronSafe);
        row.setAssignedAt(Instant.now());
        row.setOperatingDate(date);
        return row;
    }

    private void writeAudit(Request req, UUID tileId, UUID daId, AssignmentDecision decision,
                            Integer insertionPos, FeasibilityResult feasibility) {
        DaAssignmentAudit audit = new DaAssignmentAudit();
        audit.setShipmentId(req.shipmentId());
        audit.setTaskType(req.taskType());
        audit.setCityId(req.cityId());
        audit.setTileId(tileId);
        audit.setDaIdSelected(daId);
        audit.setDecision(decision);
        audit.setInsertionPos(insertionPos);
        if (feasibility != null) {
            audit.setCheapestInsertExtraSec((int) feasibility.extraTravelSeconds());
            audit.setCronSlackSec((int) feasibility.cronSlackSeconds());
            audit.setUsedOsrm(feasibility.usedOsrm());
        }
        audit.setDecidedAt(Instant.now());
        auditRepository.save(audit);
    }

    /** Renumber a DA's remaining active tasks to contiguous positions 0..n-1 in queue order. */
    private void resequence(UUID daId, LocalDate date) {
        List<DispatchQueue> active = sortedActive(daId, date);
        for (int i = 0; i < active.size(); i++) {
            active.get(i).setQueuePosition(i);
        }
        queueRepository.saveAll(active);
    }

    /** Mirror the DA's persisted active queue into the in-memory {@code DaQueue} (station view / depth). */
    private void rebuildMemQueue(UUID daId, LocalDate date) {
        QueueMirror.rebuild(daStatusService, queueRepository, daId, date);
    }

    private List<DispatchQueue> sortedActive(UUID daId, LocalDate date) {
        return queueRepository.findByDaIdAndOperatingDateAndStatusIn(daId, date, ACTIVE).stream()
                .sorted(Comparator.comparingInt(DispatchQueue::getQueuePosition))
                .toList();
    }

    private double loadScore(UUID tileId, LocalDate date) {
        TileLoadScoreResponse score = loadScoreService.getLoadScore(tileId, date);
        return score != null ? score.adjustedLoadScore() : 0.0;
    }

    private Instant earliestFrozenRetry(List<UUID> roster, LocalDate date) {
        long freezeMinutes = props.getCron().getFreezeMinutes();
        return roster.stream()
                .filter(this::isCronFrozen)
                .map(da -> cronRepository.findByDaIdAndOperatingDate(da, date).orElse(null))
                .filter(c -> c != null && c.getScheduledMeetingTime() != null)
                .map(c -> c.getScheduledMeetingTime().plus(freezeMinutes, ChronoUnit.MINUTES))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private UUID resolveTile(UUID cityId, double lat, double lon) {
        return gridService.getTileAt(cityId, lat, lon).hexId();
    }

    private boolean isAssignable(UUID daId) {
        DaStatusEnum s = daStatusService.getStatus(daId);
        return s == DaStatusEnum.IDLE || s == DaStatusEnum.IN_PROGRESS;
    }

    private boolean isCronFrozen(UUID daId) {
        DaStatusEnum s = daStatusService.getStatus(daId);
        return s == DaStatusEnum.CRON_LOCKED || s == DaStatusEnum.AT_CRON;
    }

    private int queueDepth(UUID daId) {
        var q = daStatusService.getQueue(daId);
        return q == null ? Integer.MAX_VALUE : q.getTasks().size();
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(props.getShift().getZone()));
    }

    /** Immutable bundle of one assignment request's inputs. */
    private record Request(UUID shipmentId, UUID cityId, TaskType taskType, double lat, double lon,
                           UUID tileId, String paymentMode) {}
}
