package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaLocationStub;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.StubStatus;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.repository.DaLocationStubRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the location-stub visit lifecycle + dwell math (mocked repos, no DB). */
class LocationStubServiceImplTest {

    private final DaLocationStubRepository stubRepo = mock(DaLocationStubRepository.class);
    private final DispatchQueueRepository queueRepo = mock(DispatchQueueRepository.class);
    private final DispatchProperties props = new DispatchProperties();
    private final LocationStubServiceImpl svc =
            new LocationStubServiceImpl(stubRepo, queueRepo, props);

    private final UUID da = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();

    private DispatchQueue task(TaskType type, double lat, double lon) {
        DispatchQueue t = new DispatchQueue();
        t.setDaId(da);
        t.setCityId(city);
        t.setShipmentId(UUID.randomUUID());
        t.setTaskType(type);
        t.setTaskLat(lat);
        t.setTaskLon(lon);
        t.setTileId(UUID.randomUUID());
        t.setOperatingDate(today);
        t.setStatus(TaskStatus.QUEUED);
        return t;
    }

    // ── attach ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void attachOpensANewStubWhenNoneOpen() {
        DispatchQueue t = task(TaskType.PICKUP, 12.97160, 77.59456);
        when(stubRepo.findFirstByDaIdAndOperatingDateAndLocationKeyAndStatus(any(), any(), any(), eq(StubStatus.OPEN)))
                .thenReturn(Optional.empty());
        when(stubRepo.save(any(DaLocationStub.class))).thenAnswer(i -> {
            DaLocationStub s = i.getArgument(0);
            if (s.getId() == null) {
                setId(s, UUID.randomUUID());
            }
            return s;
        });

        svc.attach(t);

        ArgumentCaptor<DaLocationStub> saved = ArgumentCaptor.forClass(DaLocationStub.class);
        verify(stubRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(StubStatus.OPEN);
        assertThat(saved.getValue().getLocationKey()).isEqualTo("12.97160,77.59456");
        assertThat(t.getStubId()).isEqualTo(saved.getValue().getId());
        verify(stubRepo).incrementTaskCount(saved.getValue().getId());
    }

    @Test
    void attachJoinsTheOpenStubAtTheSameLocation() {
        DaLocationStub open = stub(StubStatus.OPEN);
        DispatchQueue t = task(TaskType.PICKUP, 12.97160, 77.59456);
        when(stubRepo.findFirstByDaIdAndOperatingDateAndLocationKeyAndStatus(any(), any(), any(), eq(StubStatus.OPEN)))
                .thenReturn(Optional.of(open));

        svc.attach(t);

        assertThat(t.getStubId()).isEqualTo(open.getId());
        verify(stubRepo, never()).save(any());   // joined, not opened
        verify(stubRepo).incrementTaskCount(open.getId());
    }

    @Test
    void twoVisitsToTheSameLocationAreSeparateStubs() {
        // First visit: no OPEN stub → opens stub A.
        DispatchQueue first = task(TaskType.PICKUP, 12.97160, 77.59456);
        when(stubRepo.findFirstByDaIdAndOperatingDateAndLocationKeyAndStatus(any(), any(), any(), eq(StubStatus.OPEN)))
                .thenReturn(Optional.empty());
        when(stubRepo.save(any(DaLocationStub.class))).thenAnswer(i -> {
            DaLocationStub s = i.getArgument(0);
            setId(s, UUID.randomUUID());
            return s;
        });
        svc.attach(first);
        UUID firstStub = first.getStubId();

        // Later visit after A closed: still no OPEN stub → opens a DIFFERENT stub B.
        DispatchQueue later = task(TaskType.PICKUP, 12.97160, 77.59456);
        svc.attach(later);

        assertThat(later.getStubId()).isNotNull().isNotEqualTo(firstStub);
        verify(stubRepo, org.mockito.Mockito.times(2)).save(any(DaLocationStub.class));
    }

    // ── arrival ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void onArrivedStampsTheStubArrivalFromTheTask() {
        DispatchQueue t = task(TaskType.PICKUP, 12.9, 77.5);
        UUID stubId = UUID.randomUUID();
        t.setStubId(stubId);
        Instant arrived = Instant.parse("2026-09-06T06:30:00Z");
        t.setArrivedAt(arrived);

        svc.onArrived(t);

        verify(stubRepo).recordArrival(stubId, arrived);
    }

    @Test
    void onArrivedIsNoOpForAStublessTask() {
        svc.onArrived(task(TaskType.PICKUP, 12.9, 77.5));   // stubId null
        verify(stubRepo, never()).recordArrival(any(), any());
    }

    // ── terminal + close ──────────────────────────────────────────────────────────────────────────

    @Test
    void onTerminalCompletedClosesTheStubAndComputesTapDwell() {
        UUID stubId = UUID.randomUUID();
        DispatchQueue t = task(TaskType.PICKUP, 12.9, 77.5);
        t.setStubId(stubId);
        t.setStatus(TaskStatus.COMPLETED);
        t.setCompletedAt(Instant.parse("2026-09-06T06:45:00Z"));

        DaLocationStub stub = stub(StubStatus.OPEN);
        setId(stub, stubId);
        stub.setFirstArrivedAt(Instant.parse("2026-09-06T06:30:00Z"));
        stub.setLastCompletedAt(Instant.parse("2026-09-06T06:45:00Z"));   // as if recordProcessed already advanced it
        when(queueRepo.countOpenTasksInStub(stubId)).thenReturn(0L);
        when(stubRepo.findById(stubId)).thenReturn(Optional.of(stub));

        svc.onTerminal(t);

        verify(stubRepo).recordProcessed(stubId, t.getCompletedAt());
        ArgumentCaptor<DaLocationStub> saved = ArgumentCaptor.forClass(DaLocationStub.class);
        verify(stubRepo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(StubStatus.CLOSED);
        assertThat(saved.getValue().getClosedAt()).isNotNull();
        assertThat(saved.getValue().getDwellSeconds()).isEqualTo(900L);        // 15 min
    }

    @Test
    void onTerminalKeepsTheStubOpenWhileTasksRemain() {
        UUID stubId = UUID.randomUUID();
        DispatchQueue t = task(TaskType.PICKUP, 12.9, 77.5);
        t.setStubId(stubId);
        t.setStatus(TaskStatus.COMPLETED);
        t.setCompletedAt(Instant.now());
        when(queueRepo.countOpenTasksInStub(stubId)).thenReturn(1L);   // a sibling task still open

        svc.onTerminal(t);

        verify(stubRepo).recordProcessed(eq(stubId), any());
        verify(stubRepo, never()).findById(any());   // not closed
        verify(stubRepo, never()).save(any());
    }

    @Test
    void onTerminalFailedCountsAsFailedAndStillCloses() {
        UUID stubId = UUID.randomUUID();
        DispatchQueue t = task(TaskType.DELIVERY, 12.9, 77.5);
        t.setStubId(stubId);
        t.setStatus(TaskStatus.FAILED);
        t.setCompletedAt(Instant.now());
        DaLocationStub stub = stub(StubStatus.OPEN);
        setId(stub, stubId);
        when(queueRepo.countOpenTasksInStub(stubId)).thenReturn(0L);
        when(stubRepo.findById(stubId)).thenReturn(Optional.of(stub));

        svc.onTerminal(t);

        verify(stubRepo).recordFailed(eq(stubId), any());
        verify(stubRepo).save(any());   // closed even with no successful task
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private DaLocationStub stub(StubStatus status) {
        DaLocationStub s = new DaLocationStub();
        setId(s, UUID.randomUUID());
        s.setDaId(da);
        s.setCityId(city);
        s.setOperatingDate(today);
        s.setLocationKey("12.97160,77.59456");
        s.setStubLat(12.97160);
        s.setStubLon(77.59456);
        s.setStatus(status);
        s.setOpenedAt(Instant.now());
        return s;
    }

    /** BaseEntity.id has no setter; set it reflectively for the mock-repo tests. */
    private static void setId(Object entity, UUID id) {
        try {
            var f = com.oneday.common.domain.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
