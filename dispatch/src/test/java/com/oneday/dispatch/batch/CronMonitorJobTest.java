package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CronMonitorJobTest {

    private DaStatusService svc;
    private CronMonitorJob job;
    private final Instant now = Instant.parse("2026-06-20T07:40:00Z");

    @BeforeEach
    void setUp() {
        svc = mock(DaStatusService.class);
        job = new CronMonitorJob(svc, new DispatchProperties());   // freeze = 30 min default
    }

    @Test
    void locksDaInsideFreezeWindow() {
        UUID da = UUID.randomUUID();
        // Meeting 20 min away → inside the 30-min freeze window.
        stubDa(da, DaStatusEnum.IN_PROGRESS, now.plusSeconds(20 * 60));

        job.sweep(now);

        verify(svc).updateStatus(da, DaStatusEnum.CRON_LOCKED);
    }

    @Test
    void doesNotLockWhenMeetingFarAway() {
        UUID da = UUID.randomUUID();
        // Meeting 90 min away → outside the freeze window.
        stubDa(da, DaStatusEnum.IDLE, now.plusSeconds(90 * 60));

        job.sweep(now);

        verify(svc, never()).updateStatus(da, DaStatusEnum.CRON_LOCKED);
    }

    @Test
    void doesNotReLockAlreadyLockedDa() {
        UUID da = UUID.randomUUID();
        stubDa(da, DaStatusEnum.CRON_LOCKED, now.plusSeconds(10 * 60));

        job.sweep(now);

        verify(svc, never()).updateStatus(da, DaStatusEnum.CRON_LOCKED);
    }

    @Test
    void skipsDaWithoutCron() {
        UUID da = UUID.randomUUID();
        when(svc.loadedDaIds()).thenReturn(Set.of(da));
        when(svc.getStatus(da)).thenReturn(DaStatusEnum.IDLE);
        when(svc.getQueue(da)).thenReturn(new DaQueue(da, null));   // no cron

        job.sweep(now);

        verify(svc, never()).updateStatus(da, DaStatusEnum.CRON_LOCKED);
    }

    private void stubDa(UUID da, DaStatusEnum status, Instant meetingTime) {
        when(svc.loadedDaIds()).thenReturn(Set.of(da));
        when(svc.getStatus(da)).thenReturn(status);
        DaCronAssignment cron = new DaCronAssignment();
        cron.setScheduledMeetingTime(meetingTime);
        when(svc.getQueue(da)).thenReturn(new DaQueue(da, cron));
    }
}
