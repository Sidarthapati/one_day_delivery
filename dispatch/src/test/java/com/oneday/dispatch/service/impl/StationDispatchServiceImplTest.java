package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.dto.response.TileQueueResponse;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.StationDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real-Postgres tests of the station view: today (in-memory DAs) vs historical (DB), and city scope. */
@Tag("e2e")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StationDispatchServiceImplTest {

    @Autowired DispatchQueueRepository queueRepo;
    @Autowired DeferredDispatchRepository deferredRepo;
    @Autowired DaCronAssignmentRepository cronRepo;
    @Autowired DaStatusRepository daStatusRepo;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private final UUID city = UUID.randomUUID();
    private final UUID tile = UUID.randomUUID();

    private DaStatusServiceImpl daStatus;
    private StationDispatchService service;

    @BeforeEach
    void setUp() {
        DispatchProperties props = new DispatchProperties();
        daStatus = new DaStatusServiceImpl(daStatusRepo, props);
        service = new StationDispatchServiceImpl(queueRepo, deferredRepo, cronRepo, daStatus, props);
    }

    @Test
    void todayViewShowsActiveQueueDepthCronSlackAndDeferred() {
        UUID da = serveTile(city);
        persistTask(da, TaskStatus.QUEUED, 0, today);
        persistTask(da, TaskStatus.IN_PROGRESS, 1, today);
        persistTask(da, TaskStatus.COMPLETED, 2, today);   // excluded from today's active view
        persistCron(da, today, Instant.now().plus(2, ChronoUnit.HOURS));
        persistDeferred(today);

        TileQueueResponse resp = service.tileQueue(tile, today, city);

        assertThat(resp.das()).hasSize(1);
        TileQueueResponse.DaQueueView view = resp.das().get(0);
        assertThat(view.daId()).isEqualTo(da);
        assertThat(view.status()).isEqualTo("IDLE");
        assertThat(view.queueDepth()).isEqualTo(2);
        assertThat(view.queue()).hasSize(2);
        assertThat(view.cronSlackMinutes()).isBetween(118L, 120L);
        assertThat(resp.deferredCount()).isEqualTo(1);
    }

    @Test
    void foreignCityTileIsForbiddenForScopedManager() {
        serveTile(city);   // tile served by `city`
        UUID otherCity = UUID.randomUUID();
        assertThatThrownBy(() -> service.tileQueue(tile, today, otherCity))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void adminScopeNullSeesForeignTile() {
        serveTile(city);
        TileQueueResponse resp = service.tileQueue(tile, today, null);   // ADMIN
        assertThat(resp.das()).hasSize(1);
    }

    @Test
    void emptyTileReturnsNoDas() {
        TileQueueResponse resp = service.tileQueue(UUID.randomUUID(), today, null);
        assertThat(resp.das()).isEmpty();
        assertThat(resp.deferredCount()).isZero();
    }

    @Test
    void historicalDateReadsFromDbWithNullStatus() {
        LocalDate yesterday = today.minusDays(1);
        UUID da = UUID.randomUUID();   // not loaded in memory
        persistTask(da, TaskStatus.COMPLETED, 0, yesterday);

        TileQueueResponse resp = service.tileQueue(tile, yesterday, null);

        assertThat(resp.das()).hasSize(1);
        assertThat(resp.das().get(0).daId()).isEqualTo(da);
        assertThat(resp.das().get(0).status()).isNull();     // no per-date live status retained
        assertThat(resp.das().get(0).queue()).hasSize(1);    // historical shows all statuses
    }

    // ── helpers ──

    private UUID serveTile(UUID cityId) {
        UUID da = UUID.randomUUID();
        daStatus.initShift(da, cityId, today, "MORNING", null);
        daStatus.setTerritory(da, java.util.List.of(tile));
        daStatus.updateGps(da, 12.97, 77.61, Instant.now());   // → IDLE, sets live cityId
        return da;
    }

    private void persistTask(UUID da, TaskStatus status, int pos, LocalDate date) {
        DispatchQueue d = new DispatchQueue();
        d.setDaId(da);
        d.setCityId(city);
        d.setShipmentId(UUID.randomUUID());
        d.setTaskType(TaskType.PICKUP);
        d.setTaskLat(12.97);
        d.setTaskLon(77.61);
        d.setTileId(tile);
        d.setQueuePosition(pos);
        d.setStatus(status);
        d.setCronSafe(true);
        d.setOperatingDate(date);
        queueRepo.saveAndFlush(d);
    }

    private void persistCron(UUID da, LocalDate date, Instant meeting) {
        DaCronAssignment c = new DaCronAssignment();
        c.setDaId(da);
        c.setCityId(city);
        c.setOperatingDate(date);
        c.setCronVertexId(UUID.randomUUID());
        c.setMeetingLat(12.96);
        c.setMeetingLon(77.60);
        c.setScheduledMeetingTime(meeting);
        c.setStatus(CronAssignmentStatus.SCHEDULED);
        cronRepo.saveAndFlush(c);
    }

    private void persistDeferred(LocalDate date) {
        DeferredDispatch d = new DeferredDispatch();
        d.setCityId(city);
        d.setShipmentId(UUID.randomUUID());
        d.setTaskType(TaskType.PICKUP);
        d.setTileId(tile);
        d.setTaskLat(12.97);
        d.setTaskLon(77.61);
        d.setDeferReason(DeferReason.CRON_INFEASIBLE);
        d.setStatus("PENDING");
        d.setOperatingDate(date);
        deferredRepo.saveAndFlush(d);
    }
}
