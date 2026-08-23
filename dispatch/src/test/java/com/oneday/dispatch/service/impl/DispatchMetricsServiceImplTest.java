package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.dto.response.DispatchExecutionStats;
import com.oneday.dispatch.repository.DaPaceRow;
import com.oneday.dispatch.repository.DeliveryOutcome;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DispatchMetricsServiceImplTest {

    private final DispatchQueueRepository repo = mock(DispatchQueueRepository.class);
    private final DispatchMetricsServiceImpl svc = new DispatchMetricsServiceImpl(repo);
    private final LocalDate date = LocalDate.now();

    private record Outcome(long completed, long failed) implements DeliveryOutcome {
        @Override public long getCompleted() { return completed; }
        @Override public long getFailed() { return failed; }
    }

    private record Pace(UUID daId, long done, long lastHour, long pending, Instant firstAssigned)
            implements DaPaceRow {
        @Override public UUID getDaId() { return daId; }
        @Override public long getDone() { return done; }
        @Override public long getLastHour() { return lastHour; }
        @Override public long getPending() { return pending; }
        @Override public Instant getFirstAssigned() { return firstAssigned; }
    }

    @Test
    void attemptSuccessIsCompletedOverAttempts() {
        when(repo.deliveryOutcome(null, date)).thenReturn(new Outcome(18, 2));
        when(repo.paceByDa(null, date)).thenReturn(List.of());

        DispatchExecutionStats stats = svc.execution(date, null);

        assertThat(stats.deliveriesCompleted()).isEqualTo(18);
        assertThat(stats.deliveriesFailed()).isEqualTo(2);
        assertThat(stats.attemptSuccessPct()).isEqualTo(0.9); // 18 / 20
    }

    @Test
    void attemptSuccessNullWhenNoAttempts() {
        when(repo.deliveryOutcome(null, date)).thenReturn(new Outcome(0, 0));
        when(repo.paceByDa(null, date)).thenReturn(List.of());

        assertThat(svc.execution(date, null).attemptSuccessPct()).isNull();
    }

    @Test
    void avgPerHourAndPendingSortComputed() {
        Instant now = Instant.now();
        UUID busy = UUID.randomUUID(), light = UUID.randomUUID();
        when(repo.deliveryOutcome(any(), any())).thenReturn(new Outcome(0, 0));
        when(repo.paceByDa(eq(null), eq(date))).thenReturn(List.of(
                // 8 stops over 4h → 2/hr; 1 pending
                new Pace(light, 8, 1, 1, now.minus(4, ChronoUnit.HOURS)),
                // 5 stops over 5h → 1/hr; 6 pending (busiest → sorts first)
                new Pace(busy, 5, 2, 6, now.minus(5, ChronoUnit.HOURS))));

        List<DispatchExecutionStats.DaPace> das = svc.execution(date, null).das();

        assertThat(das).hasSize(2);
        assertThat(das.get(0).daId()).isEqualTo(busy);           // most pending first
        assertThat(das.get(0).avgPerHour()).isCloseTo(1.0, within(0.05));
        assertThat(das.get(1).avgPerHour()).isCloseTo(2.0, within(0.05));
    }

    @Test
    void freshDaWithTinyElapsedReadsZeroAvg() {
        Instant now = Instant.now();
        when(repo.deliveryOutcome(any(), any())).thenReturn(new Outcome(0, 0));
        when(repo.paceByDa(any(), any())).thenReturn(List.of(
                new Pace(UUID.randomUUID(), 1, 1, 3, now.minus(60, ChronoUnit.SECONDS)))); // <6min

        assertThat(svc.execution(date, null).das().get(0).avgPerHour()).isEqualTo(0.0);
    }
}
