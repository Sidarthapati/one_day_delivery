package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real-Postgres tests of the DA task-lifecycle transitions, guards, and (gated) event emission. */
@Tag("e2e")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DaTaskServiceImplTest {

    @Autowired DispatchQueueRepository queueRepo;
    @Autowired DaCronAssignmentRepository cronRepo;
    @Autowired DaStatusRepository daStatusRepo;

    private final UUID da = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final UUID tile = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();

    private DaEventProducer events;
    private DaTaskService service;

    @BeforeEach
    void setUp() {
        DispatchProperties props = new DispatchProperties();
        DaStatusServiceImpl daStatus = new DaStatusServiceImpl(daStatusRepo, props);
        daStatus.initShift(da, city, today, "MORNING", null);
        events = mock(DaEventProducer.class);
        com.oneday.dispatch.events.HubScanSeamProducer scanSeam =
                mock(com.oneday.dispatch.events.HubScanSeamProducer.class);
        com.oneday.common.port.CityMeetingModePort meetingMode =
                mock(com.oneday.common.port.CityMeetingModePort.class);
        when(meetingMode.modeFor(any())).thenReturn(com.oneday.common.domain.MeetingMode.VAN_MEETING);
        service = new DaTaskServiceImpl(queueRepo, cronRepo, daStatus, events, props, scanSeam, meetingMode);
    }

    @Test
    void enRouteMovesPickupToInProgress() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.QUEUED);
        service.markEnRoute(da, task.getId());
        assertThat(reload(task).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(reload(task).getStartedAt()).isNotNull();
    }

    @Test
    void enRouteOnWrongStatusIs409() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.markEnRoute(da, task.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void enRouteOnDeliveryTaskIs409() {
        DispatchQueue task = persist(TaskType.DELIVERY, TaskStatus.QUEUED);
        assertThatThrownBy(() -> service.markEnRoute(da, task.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void unknownTaskIs404() {
        assertThatThrownBy(() -> service.markEnRoute(da, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void anotherDasTaskIs404() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.QUEUED);
        assertThatThrownBy(() -> service.markEnRoute(UUID.randomUUID(), task.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void vanHandoffCompletesTaskAndCronAndEmits() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.IN_PROGRESS);
        persistCron(CronAssignmentStatus.SCHEDULED);

        service.recordVanHandoff(da, task.getId(), List.of("BLR-1"), UUID.randomUUID());

        assertThat(reload(task).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        DaCronAssignment cron = cronRepo.findByDaIdAndOperatingDate(da, today).orElseThrow();
        assertThat(cron.getStatus()).isEqualTo(CronAssignmentStatus.COMPLETED);
        assertThat(cron.getHandoffCompletedAt()).isNotNull();
        assertThat(cron.getParcelCountHanded()).isEqualTo(1);
        verify(events).emitVanHandoffCompleted(eq(da), eq(city), eq(task.getShipmentId()));
    }

    @Test
    void hubReturnHandoffRollsCronToNextSlotAndStaysScheduled() {
        // HUB_RETURN cron (no van) with a later return today: the handoff completes THIS leg but keeps
        // the assignment SCHEDULED, rolling the meeting to the next slot so the hard constraint holds.
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.IN_PROGRESS);
        persistHubReturnCron("10:00", java.util.List.of("10:00", "13:00"));

        service.recordVanHandoff(da, task.getId(), java.util.List.of("P-1"), null);

        DaCronAssignment cron = cronRepo.findByDaIdAndOperatingDate(da, today).orElseThrow();
        assertThat(cron.getStatus()).isEqualTo(CronAssignmentStatus.SCHEDULED);
        java.time.ZoneId zone = java.time.ZoneId.of(new DispatchProperties().getShift().getZone());
        java.time.Instant expectedNext = java.time.LocalTime.parse("13:00")
                .atDate(today).atZone(zone).toInstant();
        assertThat(cron.getScheduledMeetingTime()).isEqualTo(expectedNext);
        assertThat(cron.getParcelCountHanded()).isEqualTo(1);
    }

    @Test
    void hubReturnHandoffOnLastSlotCompletesCron() {
        // Final return of the day (no later slot) is terminal, like a van rendezvous.
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.IN_PROGRESS);
        persistHubReturnCron("19:00", java.util.List.of("19:00"));

        service.recordVanHandoff(da, task.getId(), java.util.List.of("P-1"), null);

        DaCronAssignment cron = cronRepo.findByDaIdAndOperatingDate(da, today).orElseThrow();
        assertThat(cron.getStatus()).isEqualTo(CronAssignmentStatus.COMPLETED);
    }

    @Test
    void vanHandoffWithNoScansIs422() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.IN_PROGRESS);
        assertThatThrownBy(() -> service.recordVanHandoff(da, task.getId(), List.of(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
    }

    @Test
    void failPickupEmitsPickupFailed() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.QUEUED);
        service.markFailed(da, task.getId(), "sender unreachable");
        assertThat(reload(task).getStatus()).isEqualTo(TaskStatus.FAILED);
        verify(events).emitPickupFailed(eq(da), eq(city), eq(task.getShipmentId()), eq("sender unreachable"));
    }

    @Test
    void failTerminalTaskIs409() {
        DispatchQueue task = persist(TaskType.PICKUP, TaskStatus.COMPLETED);
        assertThatThrownBy(() -> service.markFailed(da, task.getId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void dropCollectedMovesDeliveryToInProgressAndEmits() {
        DispatchQueue task = persist(TaskType.DELIVERY, TaskStatus.QUEUED);
        service.markDropCollected(da, task.getId());
        assertThat(reload(task).getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        verify(events).emitDropCollected(eq(da), eq(city), eq(task.getShipmentId()));
    }

    @Test
    void dropCompletedWithCodEmitsBothEvents() {
        DispatchQueue task = persist(TaskType.DELIVERY, TaskStatus.IN_PROGRESS);
        service.markDropCompleted(da, task.getId(), true);
        assertThat(reload(task).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(events).emitDropCompleted(eq(da), eq(city), eq(task.getShipmentId()));
        verify(events).emitCodCollected(eq(da), eq(city), eq(task.getShipmentId()));
    }

    // ── helpers ──

    private DispatchQueue persist(TaskType type, TaskStatus status) {
        DispatchQueue d = new DispatchQueue();
        d.setDaId(da);
        d.setCityId(city);
        d.setShipmentId(UUID.randomUUID());
        d.setTaskType(type);
        d.setTaskLat(12.97);
        d.setTaskLon(77.61);
        d.setTileId(tile);
        d.setQueuePosition(0);
        d.setStatus(status);
        d.setCronSafe(true);
        d.setOperatingDate(today);
        return queueRepo.saveAndFlush(d);
    }

    private void persistCron(CronAssignmentStatus status) {
        DaCronAssignment c = new DaCronAssignment();
        c.setDaId(da);
        c.setCityId(city);
        c.setOperatingDate(today);
        c.setCronVertexId(UUID.randomUUID());
        c.setMeetingLat(12.96);
        c.setMeetingLon(77.60);
        c.setScheduledMeetingTime(Instant.now().plus(2, ChronoUnit.HOURS));
        c.setStatus(status);
        cronRepo.saveAndFlush(c);
    }

    private void persistHubReturnCron(String currentSlot, java.util.List<String> meetingTimes) {
        java.time.ZoneId zone = java.time.ZoneId.of(new DispatchProperties().getShift().getZone());
        DaCronAssignment c = new DaCronAssignment();
        c.setDaId(da);
        c.setCityId(city);
        c.setOperatingDate(today);
        c.setCronVertexId(UUID.randomUUID());
        c.setMeetingLat(12.96);
        c.setMeetingLon(77.60);
        c.setScheduledMeetingTime(java.time.LocalTime.parse(currentSlot).atDate(today).atZone(zone).toInstant());
        c.setMeetingTimes(meetingTimes);
        c.setVanId(null);   // HUB_RETURN crons carry no van
        c.setStatus(CronAssignmentStatus.SCHEDULED);
        cronRepo.saveAndFlush(c);
    }

    private DispatchQueue reload(DispatchQueue task) {
        return queueRepo.findById(task.getId()).orElseThrow();
    }
}
