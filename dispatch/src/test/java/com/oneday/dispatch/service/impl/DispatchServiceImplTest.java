package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaAssignmentAudit;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DaAssignmentAuditRepository;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.service.AdjacentDaProvider;
import com.oneday.dispatch.service.AssignmentOutcome;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.CronFeasibilityService;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.service.FeasibilityResult;
import com.oneday.dispatch.service.TaskInProgressException;
import com.oneday.dispatch.service.model.DispatchTask;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-Postgres exercise of the assignment engine: actual queue insertion / position bumping /
 * resequencing / audit rows, with the feasibility engine, grid and load-score collaborators mocked
 * and a real in-memory {@link DaStatusServiceImpl}.
 */
@Tag("e2e")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DispatchServiceImplTest {

    @Autowired DispatchQueueRepository queueRepo;
    @Autowired DeferredDispatchRepository deferredRepo;
    @Autowired DaAssignmentAuditRepository auditRepo;
    @Autowired DaCronAssignmentRepository cronRepo;
    @Autowired DaStatusRepository daStatusRepo;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private final UUID city = UUID.randomUUID();
    private final UUID tile = UUID.randomUUID();

    private DaStatusServiceImpl daStatus;
    private CronFeasibilityService feasibility;
    private IntradayLoadScoreService loadScore;
    private GridService grid;
    private AdjacentDaProvider adjacent;
    private DispatchProperties props;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        props = new DispatchProperties();
        daStatus = new DaStatusServiceImpl(daStatusRepo, props);
        feasibility = mock(CronFeasibilityService.class);
        loadScore = mock(IntradayLoadScoreService.class);
        grid = mock(GridService.class);
        adjacent = mock(AdjacentDaProvider.class);
        when(adjacent.candidates(any(), any(), any())).thenReturn(List.of());
        DaEventProducer daEventProducer = new DaEventProducer(mock(com.oneday.common.kafka.EventPublisher.class), props);
        com.oneday.dispatch.metrics.DispatchMetrics metrics =
                new com.oneday.dispatch.metrics.DispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new DispatchServiceImpl(queueRepo, deferredRepo, auditRepo, cronRepo,
                daStatus, feasibility, loadScore, adjacent, grid, daEventProducer, metrics, props);
    }

    private UUID readyDa(int existingQueued) {
        UUID da = UUID.randomUUID();
        daStatus.initShift(da, city, today, "MORNING", null);
        daStatus.setTerritory(da, List.of(tile));
        daStatus.updateGps(da, 12.97, 77.61, Instant.now());   // OFFLINE → IDLE, GPS set
        for (int i = 0; i < existingQueued; i++) {
            UUID shipment = UUID.randomUUID();
            persistTask(da, shipment, TaskStatus.QUEUED, i);
            // mirror the persisted row into the in-memory queue so queueDepth reflects DB state
            daStatus.getQueue(da).getTasks().add(new DispatchTask(
                    da, shipment, TaskType.PICKUP, 12.97, 77.61, i, TaskStatus.QUEUED, null));
        }
        return da;
    }

    @Test
    void assignsToOnlyAvailableDaAndWritesAudit() {
        UUID da = readyDa(0);
        feasibleAt(0);
        UUID shipment = UUID.randomUUID();

        AssignmentResult r = service.assignPickup(shipment, city, 12.98, 77.62, tile, "PREPAID");

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.ASSIGNED);
        assertThat(r.daId()).isEqualTo(da);
        assertThat(r.queuePosition()).isZero();
        assertThat(queueRepo.findByDaIdAndOperatingDateOrderByQueuePosition(da, today)).hasSize(1);
        assertThat(auditRepo.findByShipmentId(shipment)).hasSize(1);
    }

    @Test
    void picksLeastLoadedDa() {
        UUID busy = readyDa(2);
        UUID idle = readyDa(0);
        feasibleAt(0);

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.daId()).isEqualTo(idle);
        assertThat(r.daId()).isNotEqualTo(busy);
    }

    @Test
    void insertsAtCheapestPositionAndBumpsLaterRows() {
        UUID da = readyDa(2);            // positions 0,1 already queued
        feasibleAt(1);                   // engine says cheapest insert is index 1

        // give the DA an active cron so feasibility is actually consulted
        persistCron(da, CronAssignmentStatus.SCHEDULED, Instant.now().plus(3, ChronoUnit.HOURS));

        UUID shipment = UUID.randomUUID();
        AssignmentResult r = service.assignPickup(shipment, city, 12.98, 77.62, tile, null);

        assertThat(r.queuePosition()).isEqualTo(1);
        List<DispatchQueue> queue = queueRepo.findByDaIdAndOperatingDateOrderByQueuePosition(da, today);
        assertThat(queue).extracting(DispatchQueue::getQueuePosition).containsExactly(0, 1, 2);
        DispatchQueue inserted = queue.stream().filter(q -> q.getShipmentId().equals(shipment)).findFirst().orElseThrow();
        assertThat(inserted.getQueuePosition()).isEqualTo(1);
    }

    @Test
    void defersWhenNoDaServesTile() {
        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.DEFERRED);
        assertThat(r.deferReason()).isEqualTo(com.oneday.dispatch.domain.DeferReason.NO_DA_AVAILABLE);
        assertThat(deferredRepo.findById(r.deferredId())).isPresent();
    }

    @Test
    void defersCronLockedWhenOnlyDaIsFrozen() {
        UUID da = readyDa(0);
        daStatus.updateStatus(da, DaStatusEnum.CRON_LOCKED);
        persistCron(da, CronAssignmentStatus.SCHEDULED, Instant.now().plus(20, ChronoUnit.MINUTES));

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.DEFERRED);
        assertThat(r.deferReason()).isEqualTo(com.oneday.dispatch.domain.DeferReason.CRON_LOCKED);
        assertThat(deferredRepo.findById(r.deferredId()).orElseThrow().getRetryAfter()).isNotNull();
    }

    @Test
    void defersWhenCronInfeasibleAtEveryPosition() {
        UUID da = readyDa(0);
        persistCron(da, CronAssignmentStatus.SCHEDULED, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(feasibility.checkFeasibility(any()))
                .thenReturn(new FeasibilityResult(false, 0, -100, 9999, false));

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.DEFERRED);
        assertThat(r.deferReason()).isEqualTo(com.oneday.dispatch.domain.DeferReason.CRON_INFEASIBLE);
    }

    @Test
    void completedCronSkipsFeasibilityAndAppends() {
        UUID da = readyDa(0);
        persistCron(da, CronAssignmentStatus.COMPLETED, Instant.now().minus(1, ChronoUnit.HOURS));

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.ASSIGNED);   // no cron gate post-handoff
    }

    @Test
    void cancelQueuedTaskResequencesRemaining() {
        UUID da = readyDa(0);
        feasibleAt(0);
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        service.assignPickup(keep, city, 12.98, 77.62, tile, null);
        service.assignPickup(drop, city, 12.99, 77.63, tile, null);

        service.cancelTask(drop, TaskType.PICKUP);

        List<DispatchQueue> active = queueRepo.findByDaIdAndOperatingDateAndStatusIn(
                da, today, List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS));
        assertThat(active).extracting(DispatchQueue::getQueuePosition).containsExactly(0);
        assertThat(active.get(0).getShipmentId()).isEqualTo(keep);
    }

    @Test
    void cancelInProgressTaskThrows() {
        UUID da = readyDa(0);
        UUID shipment = UUID.randomUUID();
        DispatchQueue row = persistTask(da, shipment, TaskStatus.IN_PROGRESS, 0);

        assertThatThrownBy(() -> service.cancelTask(shipment, TaskType.PICKUP))
                .isInstanceOf(TaskInProgressException.class);
        assertThat(queueRepo.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);   // unchanged
    }

    @Test
    void reassignDeferredFlipsRowToAssignedOnSuccess() {
        UUID da = readyDa(0);
        feasibleAt(0);
        // first attempt: no DA serves the tile yet → deferred
        daStatus.setTerritory(da, List.of());   // withdraw territory
        AssignmentResult deferred = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);
        assertThat(deferred.outcome()).isEqualTo(AssignmentOutcome.DEFERRED);

        // now the DA serves the tile → retry succeeds
        daStatus.setTerritory(da, List.of(tile));
        AssignmentResult retried = service.reassignDeferred(deferred.deferredId());

        assertThat(retried.outcome()).isEqualTo(AssignmentOutcome.ASSIGNED);
        assertThat(deferredRepo.findById(deferred.deferredId()).orElseThrow().getStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    void crossTerritoryAssignsToSparseNeighbourWhenBothConditionsMet() {
        props.getCrossTerritory().setEnabled(true);
        UUID overloadedDa = readyDa(0);
        UUID neighbourTile = UUID.randomUUID();
        UUID neighbourDa = UUID.randomUUID();
        daStatus.initShift(neighbourDa, city, today, "MORNING", null);
        daStatus.setTerritory(neighbourDa, List.of(neighbourTile));
        daStatus.updateGps(neighbourDa, 12.95, 77.60, Instant.now());

        // primary infeasible, neighbour feasible
        persistCron(overloadedDa, CronAssignmentStatus.SCHEDULED, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(feasibility.checkFeasibility(any())).thenReturn(new FeasibilityResult(false, 0, -1, 1, false));
        when(loadScore.getLoadScore(eq(tile), any())).thenReturn(score(tile, 2.0));            // overloaded ≥ 1.5
        when(loadScore.getLoadScore(eq(neighbourTile), any())).thenReturn(score(neighbourTile, 0.2)); // sparse < 0.8
        when(adjacent.candidates(eq(city), eq(tile), any()))
                .thenReturn(List.of(new AdjacentDaProvider.Candidate(neighbourDa, neighbourTile)));

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.CROSS_TERRITORY_ASSIGNED);
        assertThat(r.daId()).isEqualTo(neighbourDa);
        assertThat(r.crossTerritory()).isTrue();
    }

    @Test
    void crossTerritoryDefersGracefullyWhenLoadScoreFails() {
        props.getCrossTerritory().setEnabled(true);
        UUID da = readyDa(0);
        persistCron(da, CronAssignmentStatus.SCHEDULED, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(feasibility.checkFeasibility(any())).thenReturn(new FeasibilityResult(false, 0, -1, 1, false));
        when(loadScore.getLoadScore(any(), any())).thenThrow(new RuntimeException("M3 down"));

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.DEFERRED);   // skipped spill-over, didn't blow up
        assertThat(r.deferReason()).isEqualTo(com.oneday.dispatch.domain.DeferReason.CRON_INFEASIBLE);
    }

    @Test
    void crossTerritoryDefersWhenOriginNotOverloaded() {
        props.getCrossTerritory().setEnabled(true);
        UUID da = readyDa(0);
        persistCron(da, CronAssignmentStatus.SCHEDULED, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(feasibility.checkFeasibility(any())).thenReturn(new FeasibilityResult(false, 0, -1, 1, false));
        when(loadScore.getLoadScore(eq(tile), any())).thenReturn(score(tile, 0.5));   // NOT overloaded

        AssignmentResult r = service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null);

        assertThat(r.outcome()).isEqualTo(AssignmentOutcome.DEFERRED);
    }

    @Test
    void concurrentAssignmentsToSameDaSerializeIntoContiguousPositions() throws Exception {
        UUID da = readyDa(0);
        feasibleAt(0);
        int n = 20;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<AssignmentResult>> jobs = IntStream.range(0, n)
                    .mapToObj(i -> (Callable<AssignmentResult>) () ->
                            service.assignPickup(UUID.randomUUID(), city, 12.98, 77.62, tile, null))
                    .toList();
            List<Future<AssignmentResult>> done = pool.invokeAll(jobs);
            for (Future<AssignmentResult> f : done) {
                assertThat(f.get().outcome()).isEqualTo(AssignmentOutcome.ASSIGNED);
            }
        } finally {
            pool.shutdownNow();
        }

        List<DispatchQueue> queue = queueRepo.findByDaIdAndOperatingDateOrderByQueuePosition(da, today);
        assertThat(queue).hasSize(n);
        assertThat(queue).extracting(DispatchQueue::getQueuePosition)
                .containsExactlyElementsOf(IntStream.range(0, n).boxed().toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void feasibleAt(int index) {
        when(feasibility.checkFeasibility(any()))
                .thenReturn(new FeasibilityResult(true, index, 600, 120, false));
    }

    private DispatchQueue persistTask(UUID da, UUID shipment, TaskStatus status, int pos) {
        DispatchQueue d = new DispatchQueue();
        d.setDaId(da);
        d.setCityId(city);
        d.setShipmentId(shipment);
        d.setTaskType(TaskType.PICKUP);
        d.setTaskLat(12.97);
        d.setTaskLon(77.61);
        d.setTileId(tile);
        d.setQueuePosition(pos);
        d.setStatus(status);
        d.setCronSafe(true);
        d.setOperatingDate(today);
        return queueRepo.saveAndFlush(d);
    }

    private void persistCron(UUID da, CronAssignmentStatus status, Instant meeting) {
        DaCronAssignment c = new DaCronAssignment();
        c.setDaId(da);
        c.setCityId(city);
        c.setOperatingDate(today);
        c.setCronVertexId(UUID.randomUUID());
        c.setMeetingLat(12.96);
        c.setMeetingLon(77.60);
        c.setScheduledMeetingTime(meeting);
        c.setStatus(status);
        cronRepo.saveAndFlush(c);
    }

    private TileLoadScoreResponse score(UUID tileId, double adjusted) {
        return new TileLoadScoreResponse(tileId, today, 0, adjusted, "NONE");
    }
}
