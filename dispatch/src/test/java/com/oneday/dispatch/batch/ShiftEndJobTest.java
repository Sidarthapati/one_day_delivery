package com.oneday.dispatch.batch;

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
        when(svc.getLiveStatus(da)).thenReturn(new DaLiveStatus(da, city, null, null, null, DaStatusEnum.IDLE));

        DispatchQueue q1 = task(da, city, TaskStatus.QUEUED);
        DispatchQueue q2 = task(da, city, TaskStatus.QUEUED);
        // The repo query already filters to QUEUED — that IS the "don't touch IN_PROGRESS/COMPLETED" guard.
        when(queueRepo.findByDaIdAndOperatingDateAndStatusIn(eq(da), eq(today), eq(List.of(TaskStatus.QUEUED))))
                .thenReturn(List.of(q1, q2));

        job.endShift(today);

        assertThat(q1.getStatus()).isEqualTo(TaskStatus.DEFERRED);
        assertThat(q2.getStatus()).isEqualTo(TaskStatus.DEFERRED);
        verify(deferredRepo, times(2)).save(any(DeferredDispatch.class));
        verify(producer).emitTaskDeferredShiftEnded(da, city, q1.getShipmentId());
        verify(producer).emitTaskDeferredShiftEnded(da, city, q2.getShipmentId());
        verify(queueRepo).saveAll(List.of(q1, q2));
        verify(svc).updateStatus(da, DaStatusEnum.OFFLINE);
        verify(svc).flushDirtyStatuses();
        verify(svc).clearAll();
    }

    @Test
    void isIdempotentWhenNothingLoaded() {
        when(svc.loadedDaIds()).thenReturn(Set.of());   // a second run after clearAll

        job.endShift(today);

        verify(queueRepo, never()).saveAll(any());
        verify(deferredRepo, never()).save(any());
        // Final flush + clear are still safe to call.
        verify(svc).flushDirtyStatuses();
        verify(svc).clearAll();
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
