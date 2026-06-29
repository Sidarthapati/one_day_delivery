package com.oneday.dispatch.demo;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.common.port.DaCronSchedulePort;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.TerritoryHexResponse;
import com.oneday.grid.dto.response.TileDetailResponse;
import com.oneday.grid.service.GridService;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Demo-only orchestrator that drives the real M5 dispatch services in-process (no RabbitMQ / shipment
 * pipeline) so the demo UI can show M5 end-to-end: shift load, cron-meeting feasibility, cheapest-
 * insertion assignment, deferral, task lifecycle and deferred retry. {@code @Profile("!prod")}.
 *
 * <p>The DA roster + hex geometry come from the live M3 grid; each DA gets a synthesized cron meeting
 * (a hex vertex, ~90 min out) so the cron-meeting hard constraint is exercised as queues fill.</p>
 */
@Service
@org.springframework.context.annotation.Profile("!prod")
public class DispatchDemoService {

    private static final Logger log = LoggerFactory.getLogger(DispatchDemoService.class);
    // Demo cron horizon: how far ahead the synthesized/nudged van-meeting sits. Generous enough that a
    // single pickup across a large city grid (e.g. Delhi NCR, ~15 km to the cron vertex) stays
    // cron-feasible — otherwise every assignment defers CRON_INFEASIBLE. The hard constraint still bites
    // once a DA's queue fills up.
    private static final long CRON_MEETING_MINUTES_AHEAD = 240;

    private final GridService gridService;
    private final DispatchService dispatchService;
    private final DaTaskService daTaskService;
    private final DaStatusService daStatusService;
    private final DispatchQueueRepository queueRepository;
    private final DeferredDispatchRepository deferredRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusRepository daStatusRepository;
    private final DaEventProducer daEventProducer;
    /** Optional M6 bridge — when routing is on the classpath (demo profile), DAs seat on the real cron. */
    private final ObjectProvider<DaCronSchedulePort> cronSchedulePort;
    /** M4 lookup so the demo state can hide tasks for shipments cancelled after the DA picked them up. */
    private final ShipmentRepository shipmentRepository;
    private final DispatchProperties props;

    public DispatchDemoService(GridService gridService, DispatchService dispatchService,
                              DaTaskService daTaskService, DaStatusService daStatusService,
                              DispatchQueueRepository queueRepository,
                              DeferredDispatchRepository deferredRepository,
                              DaCronAssignmentRepository cronRepository,
                              DaStatusRepository daStatusRepository,
                              DaEventProducer daEventProducer,
                              ObjectProvider<DaCronSchedulePort> cronSchedulePort,
                              ShipmentRepository shipmentRepository, DispatchProperties props) {
        this.gridService = gridService;
        this.dispatchService = dispatchService;
        this.daTaskService = daTaskService;
        this.daStatusService = daStatusService;
        this.queueRepository = queueRepository;
        this.deferredRepository = deferredRepository;
        this.cronRepository = cronRepository;
        this.daStatusRepository = daStatusRepository;
        this.daEventProducer = daEventProducer;
        this.cronSchedulePort = cronSchedulePort;
        this.shipmentRepository = shipmentRepository;
        this.props = props;
    }

    // ── view model ────────────────────────────────────────────────────────────────────────────────

    public record TaskView(UUID shipmentId, String ref, String m4State, UUID tileId, int position, String status,
                           String taskType, double taskLat, double taskLon,
                           boolean cronSafe, boolean crossTerritory, Instant expectedEta) {}

    public record DaView(UUID daId, String status, Double lat, Double lon, List<UUID> hexes,
                         String cronMeetingTime, Long cronSlackMinutes,
                         Double cronVertexLat, Double cronVertexLon, UUID vanId,
                         List<String> meetingTimes, String cronStatus, Integer parcelsHandedOff,
                         Double distanceToCronKm, Long lastPingSecondsAgo,
                         int completedToday, int codParcels, List<TaskView> queue) {}

    public record DeferredView(UUID shipmentId, String ref, String taskType, UUID tileId,
                               double lat, double lon, String reason, String status,
                               String deferredAt, String retryAfter, int retryCount, String m4State) {}

    public record DispatchState(UUID cityId, LocalDate date, List<DaView> das,
                                List<DeferredView> deferred, Summary summary) {}

    public record Summary(int das, int assigned, int deferred, int cronLocked, int crossTerritory) {}

    public record AssignOutcome(UUID shipmentId, UUID tileId, double lat, double lon,
                                String outcome, UUID daId, Integer queuePosition, String deferReason) {}

    public record AssignResult(int requested, int assigned, int deferred, int crossTerritory,
                               List<AssignOutcome> outcomes) {}

    // ── actions ───────────────────────────────────────────────────────────────────────────────────

    /** Seed the in-memory shift from M3's roster + seat each DA on its (real, M6-scheduled) cron meeting. */
    @Transactional
    public DispatchState loadShift(UUID cityId, LocalDate gridDate) {
        LocalDate date = today();
        Map<UUID, double[]> hexCoords = hexCoords(cityId, gridDate);
        Map<UUID, List<UUID>> hexesByDa = territoriesByDa(cityId, gridDate);
        Map<UUID, DaCronSchedulePort.DaCron> m6Crons = m6Crons(cityId, gridDate);

        for (Map.Entry<UUID, List<UUID>> e : hexesByDa.entrySet()) {
            UUID daId = e.getKey();
            List<UUID> hexes = e.getValue();
            daStatusService.initShift(daId, cityId, date, "DEMO", null);
            daStatusService.setTerritory(daId, hexes);
            double[] home = hexCoords.getOrDefault(hexes.get(0), new double[]{0, 0});
            daStatusService.updateGps(daId, home[0], home[1], Instant.now());   // OFFLINE → IDLE
            ensureCron(daId, cityId, date, hexes, hexCoords, m6Crons.get(daId));
        }
        // Pick up anything deferred before the shift was on (the common "assigned, then loaded" order) now
        // that DAs exist — so loading the shift pulls the backlog into queues instead of leaving it stuck.
        int retried = 0;
        for (DeferredDispatch d : deferredRepository.findPendingForRetry(cityId, Instant.now())) {
            try {
                dispatchService.reassignDeferred(d.getId());
                retried++;
            } catch (RuntimeException ex) {
                log.debug("[m5-demo] load-shift retry skipped {}: {}", d.getId(), ex.getMessage());
            }
        }
        log.info("[m5-demo] loaded shift for {} DAs in city {} ({} on real M6 cron, {} deferred retried)",
                hexesByDa.size(), cityId, m6Crons.size(), retried);
        return state(cityId, gridDate);
    }

    /** Synthesize {@code count} pickups at random active hexes and run them through the assignment engine. */
    @Transactional
    public AssignResult assign(UUID cityId, LocalDate gridDate, int count) {
        List<TileDetailResponse> active = gridService.getTileDetails(cityId, gridDate).stream()
                .filter(TileDetailResponse::active).toList();
        if (active.isEmpty()) {
            return new AssignResult(0, 0, 0, 0, List.of());
        }
        List<AssignOutcome> outcomes = new ArrayList<>();
        int assigned = 0;
        int deferred = 0;
        int cross = 0;
        for (int i = 0; i < count; i++) {
            TileDetailResponse hex = active.get(ThreadLocalRandom.current().nextInt(active.size()));
            UUID shipmentId = UUID.randomUUID();
            String paymentMode = ThreadLocalRandom.current().nextBoolean() ? "PREPAID" : "COD";
            AssignmentResult r = dispatchService.assignPickup(
                    shipmentId, cityId, hex.centerLat(), hex.centerLon(), hex.id(), paymentMode);
            switch (r.outcome()) {
                case ASSIGNED -> assigned++;
                case CROSS_TERRITORY_ASSIGNED -> { assigned++; cross++; }
                case DEFERRED -> deferred++;
            }
            outcomes.add(new AssignOutcome(shipmentId, hex.id(), hex.centerLat(), hex.centerLon(),
                    r.outcome().name(), r.daId(), r.queuePosition(),
                    r.deferReason() != null ? r.deferReason().name() : null));
        }
        log.info("[m5-demo] assigned {} pickups: {} placed, {} deferred, {} cross-territory",
                count, assigned, deferred, cross);
        return new AssignResult(count, assigned, deferred, cross, outcomes);
    }

    /** Synthesize {@code count} deliveries at random active hexes → the delivery side of the engine. */
    @Transactional
    public AssignResult assignDeliveries(UUID cityId, LocalDate gridDate, int count) {
        List<TileDetailResponse> active = gridService.getTileDetails(cityId, gridDate).stream()
                .filter(TileDetailResponse::active).toList();
        if (active.isEmpty()) {
            return new AssignResult(0, 0, 0, 0, List.of());
        }
        List<AssignOutcome> outcomes = new ArrayList<>();
        int assigned = 0;
        int deferred = 0;
        int cross = 0;
        for (int i = 0; i < count; i++) {
            TileDetailResponse hex = active.get(ThreadLocalRandom.current().nextInt(active.size()));
            UUID shipmentId = UUID.randomUUID();
            AssignmentResult r = dispatchService.assignDelivery(
                    shipmentId, cityId, hex.centerLat(), hex.centerLon(), hex.id());
            switch (r.outcome()) {
                case ASSIGNED -> assigned++;
                case CROSS_TERRITORY_ASSIGNED -> { assigned++; cross++; }
                case DEFERRED -> deferred++;
            }
            outcomes.add(new AssignOutcome(shipmentId, hex.id(), hex.centerLat(), hex.centerLon(),
                    r.outcome().name(), r.daId(), r.queuePosition(),
                    r.deferReason() != null ? r.deferReason().name() : null));
        }
        log.info("[m5-demo] assigned {} deliveries: {} placed, {} deferred", count, assigned, deferred);
        return new AssignResult(count, assigned, deferred, cross, outcomes);
    }

    /** Advance each DA's lead task one step (QUEUED→en-route, IN_PROGRESS→van-handoff) to drain queues. */
    @Transactional
    public DispatchState workNext(UUID cityId, LocalDate gridDate) {
        LocalDate date = today();
        for (UUID daId : new ArrayList<>(daStatusService.loadedDaIds())) {
            List<DispatchQueue> active = queueRepository
                    .findByDaIdAndOperatingDateAndStatusIn(daId, date,
                            List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS)).stream()
                    .sorted(Comparator.comparingInt(DispatchQueue::getQueuePosition)).toList();
            if (active.isEmpty()) {
                continue;
            }
            DispatchQueue lead = active.get(0);
            try {
                if (lead.getTaskType() == TaskType.PICKUP) {
                    if (lead.getStatus() == TaskStatus.QUEUED) {
                        daTaskService.markEnRoute(daId, lead.getId());
                    } else if (lead.getStatus() == TaskStatus.IN_PROGRESS) {
                        daTaskService.recordVanHandoff(daId, lead.getId(), List.of("DEMO-SCAN"), UUID.randomUUID());
                    }
                } else {   // DELIVERY: QUEUED → drop-collected (en route) → drop-completed (some COD)
                    if (lead.getStatus() == TaskStatus.QUEUED) {
                        daTaskService.markDropCollected(daId, lead.getId());
                    } else if (lead.getStatus() == TaskStatus.IN_PROGRESS) {
                        boolean cod = (lead.getShipmentId().hashCode() & 1) == 0;
                        daTaskService.markDropCompleted(daId, lead.getId(), cod);
                    }
                }
            } catch (RuntimeException ex) {
                log.debug("[m5-demo] work-next skipped task {}: {}", lead.getId(), ex.getMessage());
            }
        }
        return state(cityId, gridDate);
    }

    /** Re-attempt every PENDING deferral that is due (DeferredRetryJob's per-call equivalent). */
    @Transactional
    public DispatchState retryDeferred(UUID cityId, LocalDate gridDate) {
        for (DeferredDispatch d : deferredRepository.findPendingForRetry(cityId, Instant.now())) {
            try {
                dispatchService.reassignDeferred(d.getId());
            } catch (RuntimeException ex) {
                log.debug("[m5-demo] retry skipped {}: {}", d.getId(), ex.getMessage());
            }
        }
        return state(cityId, gridDate);
    }

    /** Cancel a queued task (drops it from the DA's queue + resequences; emits QUEUE_REORDERED). */
    @Transactional
    public DispatchState cancelTask(UUID cityId, LocalDate gridDate, UUID shipmentId, TaskType taskType) {
        try {
            dispatchService.cancelTask(shipmentId, taskType);
        } catch (RuntimeException ex) {
            log.debug("[m5-demo] cancel skipped {}: {}", shipmentId, ex.getMessage());
        }
        return state(cityId, gridDate);
    }

    /**
     * Force a DA ABSENT (what {@code AbsentDaDetectionJob} does on a heartbeat lapse): the DA stops
     * being assignable, so new pickups in its tiles defer (NO_DA), and {@code DA_ABSENT} fires (→ M11).
     */
    @Transactional
    public DispatchState markAbsent(UUID cityId, LocalDate gridDate, UUID daId) {
        daStatusService.updateStatus(daId, DaStatusEnum.ABSENT);
        daEventProducer.emitDaAbsent(daId, cityId);
        log.info("[m5-demo] marked DA {} ABSENT", daId);
        return state(cityId, gridDate);
    }

    /**
     * End the shift (what {@code ShiftEndJob} does): every still-QUEUED task is deferred with
     * {@code SHIFT_ENDED} (→ M11 via {@code TASK_DEFERRED_SHIFT_ENDED}) and every DA goes OFFLINE.
     */
    @Transactional
    public DispatchState endShift(UUID cityId, LocalDate gridDate) {
        LocalDate date = today();
        for (UUID daId : new ArrayList<>(daStatusService.loadedDaIds())) {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            if (live == null || !cityId.equals(live.getCityId())) {
                continue;
            }
            for (DispatchQueue task : queueRepository
                    .findByDaIdAndOperatingDateAndStatusIn(daId, date, List.of(TaskStatus.QUEUED))) {
                task.setStatus(TaskStatus.DEFERRED);
                queueRepository.save(task);
                deferredRepository.save(shiftEndDeferral(task, date));
                daEventProducer.emitTaskDeferredShiftEnded(daId, cityId, task.getShipmentId());
            }
            daStatusService.updateStatus(daId, DaStatusEnum.OFFLINE);
        }
        log.info("[m5-demo] ended shift for city {}", cityId);
        return state(cityId, gridDate);
    }

    private DeferredDispatch shiftEndDeferral(DispatchQueue task, LocalDate date) {
        DeferredDispatch d = new DeferredDispatch();
        d.setCityId(task.getCityId());
        d.setShipmentId(task.getShipmentId());
        d.setTaskType(task.getTaskType());
        d.setTileId(task.getTileId());
        d.setTaskLat(task.getTaskLat());
        d.setTaskLon(task.getTaskLon());
        d.setDeferReason(DeferReason.SHIFT_ENDED);
        d.setStatus("PENDING");
        d.setOperatingDate(date);
        return d;
    }

    @Transactional(readOnly = true)
    public DispatchState state(UUID cityId, LocalDate gridDate) {
        LocalDate date = today();
        Instant now = Instant.now();
        Map<UUID, List<UUID>> hexesByDa = territoriesByDa(cityId, gridDate);

        // Batch the per-DA reads into 2 queries up front (was N×3 — ~80 round-trips for 25 DAs against
        // the remote DB, which caused thread starvation). Group/filter in memory in the loop below.
        Map<UUID, DaCronAssignment> cronByDa = cronRepository.findByOperatingDateAndCityId(date, cityId).stream()
                .collect(Collectors.toMap(DaCronAssignment::getDaId, c -> c, (a, b) -> a));
        Map<UUID, List<DispatchQueue>> tasksByDa = queueRepository.findByCityIdAndOperatingDate(cityId, date).stream()
                .collect(Collectors.groupingBy(DispatchQueue::getDaId));
        // Hide tasks whose M4 shipment was cancelled after the DA picked it up — M5 leaves an IN_PROGRESS
        // task on cancel ("for ops/M11"), but a cancelled parcel must not still count as a live pickup/drop
        // in the roster counts, ops map or dispatch board (all of which read this state).
        Set<UUID> taskShipmentIds = tasksByDa.values().stream().flatMap(List::stream)
                .map(DispatchQueue::getShipmentId).collect(Collectors.toSet());
        // One shipment lookup → drives both the cancelled filter and the shipmentId → ref map (so the
        // ops-map popup + roster can show human refs, not raw ids). Synthetic tasks have no shipment row.
        List<Shipment> taskShipments = taskShipmentIds.isEmpty() ? List.of()
                : shipmentRepository.findAllById(taskShipmentIds);
        Set<UUID> cancelledShipmentIds = taskShipments.stream()
                .filter(s -> s.getState() == ShipmentState.CANCELLED)
                .map(Shipment::getId).collect(Collectors.toSet());
        Map<UUID, String> refById = taskShipments.stream()
                .collect(Collectors.toMap(Shipment::getId, Shipment::getShipmentRef, (a, b) -> a));
        Map<UUID, String> stateById = taskShipments.stream()
                .collect(Collectors.toMap(Shipment::getId, s -> s.getState().name(), (a, b) -> a));

        List<DaView> das = new ArrayList<>();
        int cronLocked = 0;
        int crossCount = 0;
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            if (live == null || !cityId.equals(live.getCityId())) {
                continue;
            }
            DaStatusEnum st = live.getStatus();
            if (st == DaStatusEnum.CRON_LOCKED || st == DaStatusEnum.AT_CRON) {
                cronLocked++;
            }
            Optional<DaCronAssignment> cron = Optional.ofNullable(cronByDa.get(daId));
            String meetingTime = cron.map(c -> c.getScheduledMeetingTime().toString()).orElse(null);
            Long slack = cron.map(c -> Duration.between(now, c.getScheduledMeetingTime()).toMinutes()).orElse(null);

            // All of the day's tasks (every status) — for the completed/COD aggregates the active queue can't
            // show. Cancelled-after-pickup shipments are excluded so they don't linger as live pickups/drops.
            List<DispatchQueue> allTasks = tasksByDa.getOrDefault(daId, List.of()).stream()
                    .filter(q -> !cancelledShipmentIds.contains(q.getShipmentId())).toList();
            int completedToday = (int) allTasks.stream().filter(q -> q.getStatus() == TaskStatus.COMPLETED).count();
            int codParcels = (int) allTasks.stream().filter(q -> "COD".equalsIgnoreCase(q.getPaymentMode())).count();

            List<TaskView> queue = allTasks.stream()
                    .filter(q -> q.getStatus() == TaskStatus.QUEUED || q.getStatus() == TaskStatus.IN_PROGRESS)
                    .sorted(Comparator.comparingInt(DispatchQueue::getQueuePosition))
                    .map(q -> new TaskView(q.getShipmentId(), refById.get(q.getShipmentId()),
                            stateById.get(q.getShipmentId()),
                            q.getTileId(), q.getQueuePosition(),
                            q.getStatus().name(), q.getTaskType().name(), q.getTaskLat(), q.getTaskLon(),
                            q.isCronSafe(), q.isCrossTerritory(), q.getExpectedEta()))
                    .toList();
            crossCount += (int) queue.stream().filter(TaskView::crossTerritory).count();

            Double distanceKm = cron.filter(c -> live.getLat() != null)
                    .map(c -> Math.round(haversineKm(live.getLat(), live.getLon(), c.getMeetingLat(), c.getMeetingLon()) * 10.0) / 10.0)
                    .orElse(null);
            Long pingAgo = live.getLastHeartbeat() != null
                    ? Duration.between(live.getLastHeartbeat(), now).toSeconds() : null;

            das.add(new DaView(daId, st != null ? st.name() : null, live.getLat(), live.getLon(),
                    hexesByDa.getOrDefault(daId, List.of()),
                    meetingTime, slack,
                    cron.map(DaCronAssignment::getMeetingLat).orElse(null),
                    cron.map(DaCronAssignment::getMeetingLon).orElse(null),
                    cron.map(DaCronAssignment::getVanId).orElse(null),
                    cron.map(DaCronAssignment::getMeetingTimes).orElse(List.of()),
                    cron.map(c -> c.getStatus() != null ? c.getStatus().name() : null).orElse(null),
                    cron.map(DaCronAssignment::getParcelCountHanded).orElse(null),
                    distanceKm, pingAgo, completedToday, codParcels,
                    queue));
        }
        das.sort(Comparator.comparing(d -> d.daId().toString()));

        List<DeferredDispatch> pendingDeferred = deferredRepository.findByCityIdAndOperatingDate(cityId, date)
                .stream().filter(d -> "PENDING".equals(d.getStatus())).toList();
        // One lookup for the deferred shipments' ref + M4 state (they have no DA task, so they're not in refById).
        Set<UUID> defIds = pendingDeferred.stream().map(DeferredDispatch::getShipmentId).collect(Collectors.toSet());
        Map<UUID, Shipment> defShipments = defIds.isEmpty() ? Map.of()
                : shipmentRepository.findAllById(defIds).stream()
                        .collect(Collectors.toMap(Shipment::getId, s -> s, (a, b) -> a));
        List<DeferredView> deferred = pendingDeferred.stream()
                .map(d -> {
                    Shipment s = defShipments.get(d.getShipmentId());
                    return new DeferredView(d.getShipmentId(),
                            s != null ? s.getShipmentRef() : null,
                            d.getTaskType().name(), d.getTileId(), d.getTaskLat(), d.getTaskLon(),
                            d.getDeferReason().name(), d.getStatus(),
                            d.getDeferredAt() != null ? d.getDeferredAt().toString() : null,
                            d.getRetryAfter() != null ? d.getRetryAfter().toString() : null,
                            d.getRetryCount(),
                            s != null ? s.getState().name() : null);
                })
                .toList();

        int assignedTasks = das.stream().mapToInt(d -> d.queue().size()).sum();
        Summary summary = new Summary(das.size(), assignedTasks, deferred.size(), cronLocked, crossCount);
        return new DispatchState(cityId, date, das, deferred, summary);
    }

    /**
     * Targeted lookup for the customer status screen: the DA (+ its cron/van) carrying this shipment's
     * PICKUP, via 2 queries instead of building the whole-city {@link #state}. Empty if not (yet) assigned.
     * Only the cron fields of {@link DaView} are populated — that's all the status reveal needs.
     */
    @Transactional(readOnly = true)
    public Optional<DaView> daForPickup(UUID shipmentId) {
        LocalDate date = today();
        return queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, TaskType.PICKUP)
                .map(q -> {
                    UUID daId = q.getDaId();
                    Optional<DaCronAssignment> cron = cronRepository.findByDaIdAndOperatingDate(daId, date);
                    return new DaView(daId, null, null, null, List.of(),
                            cron.map(c -> c.getScheduledMeetingTime().toString()).orElse(null), null,
                            cron.map(DaCronAssignment::getMeetingLat).orElse(null),
                            cron.map(DaCronAssignment::getMeetingLon).orElse(null),
                            cron.map(DaCronAssignment::getVanId).orElse(null),
                            cron.map(DaCronAssignment::getMeetingTimes).orElse(List.of()),
                            null, null, null, null, 0, 0, List.of());
                });
    }

    /**
     * Mark a shipment's PICKUP as collected by the DA (QUEUED → IN_PROGRESS) — the M5-side effect of a
     * successful door OTP. After this the parcel is "ready for the van" (pickedUpPickups), so the demo
     * run will carry it. No-op if the task isn't found or isn't QUEUED. Returns true if it advanced.
     */
    @Transactional
    public boolean markPickedUp(UUID shipmentId) {
        return queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, TaskType.PICKUP)
                .filter(q -> q.getStatus() == TaskStatus.QUEUED)
                .map(q -> { daTaskService.markEnRoute(q.getDaId(), q.getId()); return true; })
                .orElse(false);
    }

    /** Complete the DA's PICKUP task when the parcel has been handed to the van / reached the origin hub,
     *  so it leaves the DA's active queue. Direct status set (no event) — demo-only, mirrors markDelivering. */
    @Transactional
    public boolean markPickupCompleted(UUID shipmentId) {
        return queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, TaskType.PICKUP)
                .filter(q -> q.getStatus() == TaskStatus.IN_PROGRESS)
                .map(q -> {
                    q.setStatus(TaskStatus.COMPLETED);
                    q.setCompletedAt(java.time.Instant.now());
                    queueRepository.save(q);
                    return true;
                })
                .orElse(false);
    }

    /** Move the DA's DELIVERY task QUEUED → IN_PROGRESS when the drop goes out for delivery, so the
     *  board/map/DA-app show it in-progress in sync with the M4 DROP_COLLECTED state. Sets the status
     *  directly (not via {@code daTaskService}) on purpose: {@code markEnRoute} is PICKUP-only, and
     *  {@code markDropCollected} would re-emit a DROP_COLLECTED DA event for a shipment M4 has already
     *  transitioned (→ a redundant event that orders would reject). This is a demo-only in-place update. */
    @Transactional
    public boolean markDelivering(UUID shipmentId) {
        return queueRepository.findActiveByShipmentIdAndTaskType(shipmentId, TaskType.DELIVERY)
                .filter(q -> q.getStatus() == TaskStatus.QUEUED)
                .map(q -> {
                    q.setStatus(TaskStatus.IN_PROGRESS);
                    q.setStartedAt(java.time.Instant.now());
                    queueRepository.save(q);
                    return true;
                })
                .orElse(false);
    }

    /** Wipe the demo's M5 state for the city so it can be re-run. */
    @Transactional
    public void reset(UUID cityId, LocalDate gridDate) {
        LocalDate date = today();
        queueRepository.deleteAll(queueRepository.findByCityIdAndOperatingDate(cityId, date));
        deferredRepository.deleteAll(deferredRepository.findByCityIdAndOperatingDate(cityId, date));
        cronRepository.deleteAll(cronRepository.findByOperatingDateAndCityId(date, cityId));
        daStatusRepository.deleteAll(
                daStatusRepository.findByCityIdAndShiftDateAndStatusIn(cityId, date, List.of(DaStatusEnum.values())));
        daStatusService.clearAll();
        log.info("[m5-demo] reset M5 state for city {}", cityId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Ensure the DA has a cron meeting for the day. Prefer the <b>real</b> rendezvous M6 published over the
     * bus — it already lands in {@code da_cron_assignment} via {@link com.oneday.dispatch.events.DaCronScheduledConsumer},
     * carrying the true meeting vertex + {@code vanId}. Keeping it means the assignment engine checks
     * feasibility against the actual van schedule and the map can draw the DA at the real handoff point,
     * linked to its van. Only synthesize a meeting (≈90 min out at a mid-territory hex vertex) when M6
     * hasn't scheduled this DA. Either way the meeting is nudged into the future so the cron-feasibility
     * hard constraint stays demoable when the plan's wall-clock time has already passed.
     */
    /** M6's per-DA cron schedule for the date (the real van rendezvous), keyed by DA; empty if no plan. */
    private Map<UUID, DaCronSchedulePort.DaCron> m6Crons(UUID cityId, LocalDate gridDate) {
        DaCronSchedulePort port = cronSchedulePort.getIfAvailable();
        if (port == null) {
            return Map.of();
        }
        return port.cronsForCity(cityId, gridDate).stream()
                .collect(Collectors.toMap(DaCronSchedulePort.DaCron::daId, c -> c, (a, b) -> a));
    }

    private void ensureCron(UUID daId, UUID cityId, LocalDate date, List<UUID> hexes,
                            Map<UUID, double[]> hexCoords, DaCronSchedulePort.DaCron m6) {
        Instant future = Instant.now().plus(CRON_MEETING_MINUTES_AHEAD, ChronoUnit.MINUTES);
        DaCronAssignment cron = cronRepository.findByDaIdAndOperatingDate(daId, date).orElseGet(DaCronAssignment::new);
        cron.setDaId(daId);
        cron.setCityId(cityId);
        cron.setOperatingDate(date);
        cron.setStatus(CronAssignmentStatus.SCHEDULED);

        if (m6 != null && !(m6.meetingLat() == 0 && m6.meetingLon() == 0)) {
            // Prefer M6's real rendezvous: the DA seats on its actual meeting vertex, linked to its van.
            cron.setCronVertexId(m6.cronVertexId());
            cron.setMeetingLat(m6.meetingLat());
            cron.setMeetingLon(m6.meetingLon());
            cron.setVanId(m6.vanId());
            List<String> times = m6.meetingTimes().isEmpty()
                    ? List.of(LocalTime.ofInstant(future, zone()).withSecond(0).withNano(0).toString())
                    : m6.meetingTimes();
            cron.setMeetingTimes(times);
            Instant scheduled = earliestMeetingInstant(times, date);
            // Keep it ahead of the demo clock so the cron-feasibility constraint stays exercisable.
            cron.setScheduledMeetingTime(scheduled.isAfter(Instant.now()) ? scheduled : future);
            cronRepository.save(cron);
            return;
        }
        // No M6 cron for this DA — synthesize one at a mid-territory hex vertex (no van link).
        double[] vertex = hexCoords.getOrDefault(hexes.get(hexes.size() / 2), new double[]{0, 0});
        cron.setCronVertexId(UUID.randomUUID());
        cron.setMeetingLat(vertex[0]);
        cron.setMeetingLon(vertex[1]);
        cron.setVanId(null);
        cron.setScheduledMeetingTime(future);
        cron.setMeetingTimes(List.of(LocalTime.ofInstant(future, zone()).withSecond(0).withNano(0).toString()));
        cronRepository.save(cron);
    }

    /** Earliest of the day's meeting times as an instant in the shift zone (falls back to ~90 min out). */
    private Instant earliestMeetingInstant(List<String> times, LocalDate date) {
        return times.stream().map(LocalTime::parse).min(Comparator.naturalOrder())
                .map(t -> t.atDate(date).atZone(zone()).toInstant())
                .orElse(Instant.now().plus(CRON_MEETING_MINUTES_AHEAD, ChronoUnit.MINUTES));
    }

    /**
     * DA → hexes for the date. Reads M3's APPROVED territories first (the state the Execution tab
     * leaves behind, also M6's planner input); falls back to ACTIVE assignments (if the date was
     * "gone live" / activated). Either way the M5 demo finds the same roster the city is planned with.
     */
    private Map<UUID, List<UUID>> territoriesByDa(UUID cityId, LocalDate gridDate) {
        Map<UUID, List<UUID>> approved = gridService.getDaTerritories(cityId, gridDate).stream()
                .collect(Collectors.toMap(DaTerritoryResponse::daId,
                        t -> t.hexes().stream().map(TerritoryHexResponse::hexId).toList(), (a, b) -> a));
        if (!approved.isEmpty()) {
            return approved;
        }
        return gridService.getActiveAssignments(cityId, gridDate).stream()
                .collect(Collectors.groupingBy(AssignmentResponse::daId,
                        Collectors.mapping(AssignmentResponse::hexId, Collectors.toList())));
    }

    private Map<UUID, double[]> hexCoords(UUID cityId, LocalDate gridDate) {
        return gridService.getTileDetails(cityId, gridDate).stream()
                .collect(Collectors.toMap(TileDetailResponse::id,
                        t -> new double[]{t.centerLat(), t.centerLon()}, (a, b) -> a));
    }

    /** Great-circle distance in km — DA's live position to its cron meeting vertex (demo readout only). */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private LocalDate today() {
        return LocalDate.now(zone());
    }

    private ZoneId zone() {
        return ZoneId.of(props.getShift().getZone());
    }
}
