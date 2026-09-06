package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaLocationStub;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.StubStatus;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.dto.response.DaLocationStubView;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaAssignmentAuditRepository;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.repository.DaLocationStubRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.AdjacentDaProvider;
import com.oneday.dispatch.service.CronFeasibilityService;
import com.oneday.dispatch.service.DispatchService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-Postgres end-to-end for the DA location-stub (time-at-location) feature: a real assignment opens a
 * visit, the real task lifecycle closes it with a tap dwell, a return opens a fresh visit, the real GPS
 * rollup fills the cross-check, and the real read model returns it — all against the live V5_20 schema
 * (partial-unique-on-OPEN index, LEAST/GREATEST rollups). Collaborators outside the feature are mocked.
 */
@Tag("e2e")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LocationStubE2ETest {

    @Autowired DispatchQueueRepository queueRepo;
    @Autowired DaLocationStubRepository stubRepo;
    @Autowired DaGpsPingRepository gpsRepo;
    @Autowired DeferredDispatchRepository deferredRepo;
    @Autowired DaAssignmentAuditRepository auditRepo;
    @Autowired DaCronAssignmentRepository cronRepo;
    @Autowired DaStatusRepository daStatusRepo;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private final UUID city = UUID.randomUUID();
    private final UUID tile = UUID.randomUUID();
    // One doorstep (rounds to the same 5dp location key); a second, distinct location.
    private static final double LAT = 12.97160, LON = 77.59456;
    private static final double LAT2 = 12.98000, LON2 = 77.60000;

    private DaStatusServiceImpl daStatus;
    private LocationStubServiceImpl stubService;
    private DispatchService dispatch;
    private DaTaskServiceImpl taskService;
    private DispatchMetricsServiceImpl metricsService;

    private com.oneday.common.port.ShipmentRefPort refPort;

    @BeforeEach
    void setUp() {
        DispatchProperties props = new DispatchProperties();
        daStatus = new DaStatusServiceImpl(daStatusRepo, gpsRepo, props);

        stubService = new LocationStubServiceImpl(stubRepo, queueRepo, props);

        CronFeasibilityService feasibility = mock(CronFeasibilityService.class);
        IntradayLoadScoreService loadScore = mock(IntradayLoadScoreService.class);
        GridService grid = mock(GridService.class);
        AdjacentDaProvider adjacent = mock(AdjacentDaProvider.class);
        when(adjacent.candidates(any(), any(), any())).thenReturn(List.of());
        DaEventProducer events = new DaEventProducer(mock(com.oneday.common.kafka.EventPublisher.class), props);
        com.oneday.dispatch.metrics.DispatchMetrics metrics =
                new com.oneday.dispatch.metrics.DispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        QueueReorderService reorder = mock(QueueReorderService.class);
        com.oneday.dispatch.events.HubScanSeamProducer scanSeam = mock(com.oneday.dispatch.events.HubScanSeamProducer.class);
        com.oneday.common.port.CityMeetingModePort meetingMode = mock(com.oneday.common.port.CityMeetingModePort.class);
        refPort = mock(com.oneday.common.port.ShipmentRefPort.class);
        when(refPort.refsFor(any())).thenReturn(java.util.Map.of());
        com.oneday.common.port.ShipmentContactPort contactPort = mock(com.oneday.common.port.ShipmentContactPort.class);
        when(contactPort.contactsFor(any())).thenReturn(java.util.Map.of());
        com.oneday.common.port.ShipmentSlaPort slaPort = mock(com.oneday.common.port.ShipmentSlaPort.class);
        when(slaPort.slaFor(any())).thenReturn(java.util.Map.of());
        com.oneday.common.port.DaDirectoryPort directory = mock(com.oneday.common.port.DaDirectoryPort.class);
        when(directory.contactsFor(any())).thenReturn(java.util.Map.of());

        dispatch = new DispatchServiceImpl(queueRepo, deferredRepo, auditRepo, cronRepo, daStatus,
                feasibility, loadScore, adjacent, grid, events, metrics, reorder, stubService, props);

        taskService = new DaTaskServiceImpl(queueRepo, cronRepo, daStatus, events, props, scanSeam,
                refPort, contactPort, reorder, meetingMode, stubService, stubRepo);

        metricsService = new DispatchMetricsServiceImpl(queueRepo, stubRepo, directory, slaPort, refPort,
                contactPort, daStatus);
    }

    private UUID rosteredDa() {
        UUID da = UUID.randomUUID();
        daStatus.initShift(da, city, today, "MORNING", null);
        daStatus.setTerritory(da, List.of(tile));
        daStatus.updateGps(da, LAT, LON, Instant.now());   // OFFLINE → IDLE
        return da;
    }

    /** Drive a DELIVERY task through arrive → collect → complete (no cron/scan needed). */
    private void deliver(UUID da, UUID taskId) {
        taskService.markArrivedAtStop(da, taskId);
        taskService.markDropCollected(da, taskId);
        taskService.markDropCompleted(da, taskId, false);
    }

    private List<DispatchQueue> tasksOf(UUID da) {
        return queueRepo.findByDaIdAndOperatingDateOrderByQueuePosition(da, today);
    }

    @Test
    void twoTasksAtOneLocationShareOneOpenStub() {
        UUID da = rosteredDa();
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);

        List<DaLocationStub> stubs = stubRepo.findByDaIdAndOperatingDateOrderByOpenedAtAsc(da, today);
        assertThat(stubs).hasSize(1);
        DaLocationStub stub = stubs.get(0);
        assertThat(stub.getStatus()).isEqualTo(StubStatus.OPEN);
        assertThat(stub.getTaskCount()).isEqualTo(2);
        assertThat(stub.getLocationKey()).isEqualTo("12.97160,77.59456");
        assertThat(tasksOf(da)).allSatisfy(t -> assertThat(t.getStubId()).isEqualTo(stub.getId()));
    }

    @Test
    void completingEveryTaskClosesTheStubWithATapDwell() {
        UUID da = rosteredDa();
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        List<DispatchQueue> tasks = tasksOf(da);

        deliver(da, tasks.get(0).getId());
        // First delivery done, sibling still open → stub stays OPEN.
        DaLocationStub mid = stubRepo.findById(tasks.get(0).getStubId()).orElseThrow();
        assertThat(mid.getStatus()).isEqualTo(StubStatus.OPEN);
        assertThat(mid.getProcessedCount()).isEqualTo(1);

        deliver(da, tasks.get(1).getId());
        DaLocationStub closed = stubRepo.findById(tasks.get(0).getStubId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(StubStatus.CLOSED);
        assertThat(closed.getProcessedCount()).isEqualTo(2);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getFirstArrivedAt()).isNotNull();
        assertThat(closed.getLastCompletedAt()).isNotNull();
        assertThat(closed.getDwellSeconds()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void aReturnToTheSameLocationOpensAFreshStub() {
        UUID da = rosteredDa();
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        UUID firstStub = tasksOf(da).get(0).getStubId();
        deliver(da, tasksOf(da).get(0).getId());   // closes the first visit
        assertThat(stubRepo.findById(firstStub).orElseThrow().getStatus()).isEqualTo(StubStatus.CLOSED);

        // The DA is sent back to the same doorstep later → a NEW visit ticket.
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);

        List<DaLocationStub> stubs = stubRepo.findByDaIdAndOperatingDateOrderByOpenedAtAsc(da, today);
        assertThat(stubs).hasSize(2);
        assertThat(stubs).extracting(DaLocationStub::getId).doesNotHaveDuplicates();
        DaLocationStub second = stubs.get(1);
        assertThat(second.getId()).isNotEqualTo(firstStub);
        assertThat(second.getStatus()).isEqualTo(StubStatus.OPEN);
    }

    @Test
    void distinctLocationsGetDistinctStubs() {
        UUID da = rosteredDa();
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT2, LON2, tile, null, null);
        assertThat(stubRepo.findByDaIdAndOperatingDateOrderByOpenedAtAsc(da, today)).hasSize(2);
    }

    @Test
    void listStubsReturnsTheDaTicketsWithTheirTasks() {
        UUID da = rosteredDa();
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT2, LON2, tile, null, null);

        List<com.oneday.dispatch.dto.response.DaStubView> tickets = taskService.listStubs(da, today);

        // Two doorsteps → two tickets; the first holds both parcels, server-grouped by stub id.
        assertThat(tickets).hasSize(2);
        assertThat(tickets.get(0).status()).isEqualTo("OPEN");
        assertThat(tickets.get(0).taskCount()).isEqualTo(2);
        assertThat(tickets.get(0).items()).hasSize(2);
        assertThat(tickets.get(0).items())
                .allSatisfy(i -> assertThat(i.stubId()).isEqualTo(tickets.get(0).stubId()));
        assertThat(tickets.get(1).items()).hasSize(1);
    }

    @Test
    void dwellReadReturnsEachVisitWithItsItems() {
        UUID da = rosteredDa();
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        dispatch.assignDelivery(UUID.randomUUID(), city, LAT, LON, tile, null, null);
        for (DispatchQueue t : tasksOf(da)) {
            deliver(da, t.getId());
        }

        List<DaLocationStubView> view = metricsService.daDwell(da, today, null);
        assertThat(view).hasSize(1);
        DaLocationStubView v = view.get(0);
        assertThat(v.status()).isEqualTo("CLOSED");
        assertThat(v.taskCount()).isEqualTo(2);
        assertThat(v.processedCount()).isEqualTo(2);
        assertThat(v.items()).hasSize(2);
        assertThat(v.items()).allSatisfy(i -> assertThat(i.taskType()).isEqualTo("DELIVERY"));
        assertThat(v.dwellSeconds()).isNotNull();
    }
}
