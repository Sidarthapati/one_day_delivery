package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeferredRetryJobTest {

    private DispatchService dispatchService;
    private DeferredDispatchRepository deferredRepo;
    private DaStatusService daStatusService;
    private DaEventProducer events;
    private DispatchProperties props;
    private DeferredRetryJob job;

    private final UUID da = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatchService = mock(DispatchService.class);
        deferredRepo = mock(DeferredDispatchRepository.class);
        daStatusService = mock(DaStatusService.class);
        events = mock(DaEventProducer.class);
        props = new DispatchProperties();
        job = new DeferredRetryJob(dispatchService, deferredRepo, daStatusService, events, props);
    }

    private void shiftLoaded() {
        when(daStatusService.loadedDaIds()).thenReturn(Set.of(da));
        when(daStatusService.getLiveStatus(da))
                .thenReturn(new DaLiveStatus(da, city, 12.9, 77.6, Instant.now(), DaStatusEnum.IDLE));
    }

    private DeferredDispatch pending(int retryCount) {
        DeferredDispatch d = new DeferredDispatch();
        d.setCityId(city);
        d.setShipmentId(UUID.randomUUID());
        d.setDeferReason(DeferReason.CRON_INFEASIBLE);
        d.setStatus("PENDING");
        d.setRetryCount(retryCount);
        return d;
    }

    @Test
    void noOpOutsideShiftHours() {
        when(daStatusService.loadedDaIds()).thenReturn(Set.of());
        job.retry();
        verifyNoInteractions(dispatchService, deferredRepo);
    }

    @Test
    void successfulRetryNeitherBumpsNorEscalates() {
        shiftLoaded();
        DeferredDispatch d = pending(0);
        when(deferredRepo.findPendingForRetry(eq(city), any())).thenReturn(List.of(d));
        when(dispatchService.reassignDeferred(any())).thenReturn(AssignmentResult.assigned(da, 0));

        job.retry();

        assertThat(d.getRetryCount()).isZero();
        verify(deferredRepo, never()).save(any());          // success is persisted by reassignDeferred
        verify(events, never()).emitTaskDeferredShiftEnded(any(), any(), any());
    }

    @Test
    void failedRetryBumpsRetryAfterAndCount() {
        shiftLoaded();
        DeferredDispatch d = pending(0);
        when(deferredRepo.findPendingForRetry(eq(city), any())).thenReturn(List.of(d));
        when(dispatchService.reassignDeferred(any()))
                .thenReturn(AssignmentResult.deferred(UUID.randomUUID(), DeferReason.CRON_INFEASIBLE));

        job.retry();

        assertThat(d.getRetryCount()).isEqualTo(1);
        assertThat(d.getRetryAfter()).isNotNull();
        assertThat(d.getStatus()).isEqualTo("PENDING");
        verify(deferredRepo).save(d);
        verify(events, never()).emitTaskDeferredShiftEnded(any(), any(), any());
    }

    @Test
    void escalatesAfterMaxRetries() {
        shiftLoaded();
        DeferredDispatch d = pending(props.getDeferred().getMaxRetries() - 1);   // one short of the cap
        when(deferredRepo.findPendingForRetry(eq(city), any())).thenReturn(List.of(d));
        when(dispatchService.reassignDeferred(any()))
                .thenReturn(AssignmentResult.deferred(UUID.randomUUID(), DeferReason.CRON_INFEASIBLE));

        job.retry();

        assertThat(d.getRetryCount()).isEqualTo(props.getDeferred().getMaxRetries());
        assertThat(d.getStatus()).isEqualTo("ESCALATED");
        assertThat(d.getEscalatedAt()).isNotNull();
        verify(events).emitTaskDeferredShiftEnded(eq(null), eq(city), eq(d.getShipmentId()));
    }
}
