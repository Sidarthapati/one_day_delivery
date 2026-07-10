package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbsentDaDetectionJobTest {

    private DaStatusService svc;
    private DaEventProducer producer;
    private AbsentDaDetectionJob job;
    private final Instant now = Instant.parse("2026-06-20T08:00:00Z");

    @BeforeEach
    void setUp() {
        svc = mock(DaStatusService.class);
        producer = mock(DaEventProducer.class);
        job = new AbsentDaDetectionJob(svc, producer, new DispatchProperties());   // threshold 15 min
    }

    @Test
    void marksDaAbsentWhenHeartbeatLapsed() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        // Silent 20 min > 15-min threshold.
        stubDa(da, city, DaStatusEnum.IN_PROGRESS, now.minusSeconds(20 * 60));

        job.sweep(now);

        verify(svc).updateStatus(da, DaStatusEnum.ABSENT);
        verify(producer).emitDaAbsent(da, city);
    }

    @Test
    void doesNotFlagRecentHeartbeat() {
        UUID da = UUID.randomUUID();
        stubDa(da, UUID.randomUUID(), DaStatusEnum.IDLE, now.minusSeconds(5 * 60));   // 5 min ago

        job.sweep(now);

        verify(svc, never()).updateStatus(da, DaStatusEnum.ABSENT);
        verify(producer, never()).emitDaAbsent(da, null);
    }

    @Test
    void skipsOfflineAndAlreadyAbsent() {
        UUID offline = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        when(svc.loadedDaIds()).thenReturn(Set.of(offline, absent));
        when(svc.getStatus(offline)).thenReturn(DaStatusEnum.OFFLINE);
        when(svc.getStatus(absent)).thenReturn(DaStatusEnum.ABSENT);

        job.sweep(now);

        verify(svc, never()).updateStatus(offline, DaStatusEnum.ABSENT);
        verify(svc, never()).updateStatus(absent, DaStatusEnum.ABSENT);
        verify(producer, never()).emitDaAbsent(offline, null);
    }

    private void stubDa(UUID da, UUID city, DaStatusEnum status, Instant lastHeartbeat) {
        when(svc.loadedDaIds()).thenReturn(Set.of(da));
        when(svc.getStatus(da)).thenReturn(status);
        DaLiveStatus live = new DaLiveStatus(da, city, 12.9, 77.6, lastHeartbeat, status);
        when(svc.getLiveStatus(da)).thenReturn(live);
    }
}
