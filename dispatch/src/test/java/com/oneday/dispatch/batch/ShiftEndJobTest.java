package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShiftEndJobTest {

    private DaStatusService svc;
    private DispatchQueueRepository queueRepo;
    private DeferredDispatchRepository deferredRepo;
    private DaEventProducer producer;
    private ShiftEndJob job;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        svc = mock(DaStatusService.class);
        queueRepo = mock(DispatchQueueRepository.class);
        deferredRepo = mock(DeferredDispatchRepository.class);
        producer = mock(DaEventProducer.class);
        job = new ShiftEndJob(svc, queueRepo, deferredRepo, producer);
    }

    @Test
    void defersQueuedTasksSetsOfflineFlushesAndClears() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        when(svc.loadedDaIds()).thenReturn(Set.of(da));
        when(svc.getLiveStatus(da)).thenReturn(liveOnShift(da, city, Shift.SHIFT_1));

        DispatchQueue q1 = task(da, city, TaskStatus.QUEUED);
        DispatchQueue q2 = task(da, city, TaskStatus.QUEUED);
        // The repo query already filters to QUEUED — that IS the "don't touch IN_PROGRESS/COMPLETED" guard.
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(eq(da), eq(today), eq(List.of(TaskStatus.QUEUED))))
                .thenReturn(List.of(q1, q2));

        job.endShift(today, Shift.SHIFT_1);

        assertThat(q1.getStatus()).isEqualTo(TaskStatus.DEFERRED);
        assertThat(q2.getStatus()).isEqualTo(TaskStatus.DEFERRED);
        verify(deferredRepo, times(2)).save(any(DeferredDispatch.class));
        verify(producer).emitTaskDeferredShiftEnded(da, city, q1.getShipmentId());
        verify(producer).emitTaskDeferredShiftEnded(da, city, q2.getShipmentId());
        verify(queueRepo).saveAll(List.of(q1, q2));
        verify(svc).updateStatus(da, DaStatusEnum.OFFLINE);
        verify(svc).flushDirtyStatuses();
        verify(svc).clear(da);
    }

    @Test
    void endingShift1DoesNotTearDownShift2Roster() {
        UUID da1 = UUID.randomUUID();   // SHIFT_1 — should be ended
        UUID da2 = UUID.randomUUID();   // SHIFT_2 — must survive
        UUID city = UUID.randomUUID();
        when(svc.loadedDaIds()).thenReturn(Set.of(da1, da2));
        when(svc.getLiveStatus(da1)).thenReturn(liveOnShift(da1, city, Shift.SHIFT_1));
        when(svc.getLiveStatus(da2)).thenReturn(liveOnShift(da2, city, Shift.SHIFT_2));
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(any(), eq(today), any())).thenReturn(List.of());

        job.endShift(today, Shift.SHIFT_1);

        // Only the SHIFT_1 DA is offlined + cleared; the SHIFT_2 DA is untouched.
        verify(svc).updateStatus(da1, DaStatusEnum.OFFLINE);
        verify(svc).clear(da1);
        verify(svc, never()).updateStatus(eq(da2), any());
        verify(svc, never()).clear(da2);
    }

    @Test
    void isIdempotentWhenNothingLoaded() {
        when(svc.loadedDaIds()).thenReturn(Set.of());   // a second run after everyone was cleared

        job.endShift(today, Shift.SHIFT_1);

        verify(queueRepo, never()).saveAll(any());
        verify(deferredRepo, never()).save(any());
        // Final flush is still safe to call; nothing to clear.
        verify(svc).flushDirtyStatuses();
        verify(svc, never()).clear(any());
    }

    @Test
    void shift2LeftoversRollToTomorrow() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        when(svc.loadedDaIds()).thenReturn(Set.of(da));
        when(svc.getLiveStatus(da)).thenReturn(liveOnShift(da, city, Shift.SHIFT_2));
        DispatchQueue q = task(da, city, TaskStatus.QUEUED);
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(eq(da), eq(today), eq(List.of(TaskStatus.QUEUED))))
                .thenReturn(List.of(q));

        job.endShift(today, Shift.SHIFT_2);

        org.mockito.ArgumentCaptor<DeferredDispatch> cap =
                org.mockito.ArgumentCaptor.forClass(DeferredDispatch.class);
        verify(deferredRepo).save(cap.capture());
        // The SHIFT_2 leftover is dated tomorrow so tomorrow's SHIFT_1 (+ the date-scoped station view) sees it.
        assertThat(cap.getValue().getOperatingDate()).isEqualTo(today.plusDays(1));
    }

    private DaLiveStatus liveOnShift(UUID da, UUID city, Shift shift) {
        return new DaLiveStatus(da, city, null, null, null, DaStatusEnum.IDLE, shift.name());
    }

    private DispatchQueue task(UUID da, UUID city, TaskStatus status) {
        DispatchQueue d = new DispatchQueue();
        d.setDaId(da);
        d.setCityId(city);
        d.setShipmentId(UUID.randomUUID());
        d.setTaskType(TaskType.PICKUP);
        d.setTileId(UUID.randomUUID());
        d.setTaskLat(12.9);
        d.setTaskLon(77.6);
        d.setStatus(status);
        d.setOperatingDate(today);
        return d;
    }
}
