package com.oneday.dispatch.service.impl;

import com.oneday.common.port.DaDirectoryPort.DaContact;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.dto.response.DaDetailResponse;
import com.oneday.dispatch.dto.response.DispatchExecutionStats;
import com.oneday.dispatch.repository.DaPaceRow;
import com.oneday.dispatch.repository.DeliveryOutcome;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DispatchMetricsServiceImplTest {

    private final DispatchQueueRepository repo = mock(DispatchQueueRepository.class);
    private final com.oneday.common.port.DaDirectoryPort directory =
            mock(com.oneday.common.port.DaDirectoryPort.class);
    private final DispatchMetricsServiceImpl svc = new DispatchMetricsServiceImpl(repo, directory);
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

    private record Day(LocalDate day, long done, long failed)
            implements com.oneday.dispatch.repository.DaDayStopsRow {
        @Override public LocalDate getDay() { return day; }
        @Override public long getDone() { return done; }
        @Override public long getFailed() { return failed; }
    }

    private DispatchQueue task(UUID cityId, TaskStatus status, Instant eta) {
        DispatchQueue t = new DispatchQueue();
        t.setDaId(UUID.randomUUID());
        t.setCityId(cityId);
        t.setShipmentId(UUID.randomUUID());
        t.setTaskType(TaskType.DELIVERY);
        t.setStatus(status);
        t.setExpectedEta(eta);
        t.setQueuePosition(0);
        t.setOperatingDate(date);
        return t;
    }

    @Test
    void daDetailSortsByUrgencyAndAttachesIdentity() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        Instant now = Instant.now();
        when(repo.findByDaIdAndOperatingDateOrderByQueuePosition(da, date)).thenReturn(List.of(
                task(city, TaskStatus.COMPLETED, null),                       // DONE
                task(city, TaskStatus.FAILED, null),                          // RED
                task(city, TaskStatus.IN_PROGRESS, now.plusSeconds(3600)),    // AMBER (on-track)
                task(city, TaskStatus.QUEUED, now.minusSeconds(3600))));      // RED (past ETA)
        when(repo.paceByDa(null, date)).thenReturn(List.of());
        when(repo.paceByDaOverDays(eq(da), any(), eq(null))).thenReturn(List.of());
        when(directory.contactsFor(List.of(da)))
                .thenReturn(Map.of(da, new DaContact("Ravi Kumar", "+919000000000")));

        DaDetailResponse d = svc.daDetail(da, date, null);

        assertThat(d.name()).isEqualTo("Ravi Kumar");
        assertThat(d.phone()).isEqualTo("+919000000000");
        assertThat(d.completed()).isEqualTo(1);
        assertThat(d.failed()).isEqualTo(1);
        // stable sort: FAILED then past-ETA QUEUED (both RED), then AMBER, then DONE
        assertThat(d.tasks()).extracting(DaDetailResponse.DaTaskItem::urgency)
                .containsExactly("RED", "RED", "AMBER", "DONE");
    }

    @Test
    void historyIsSevenDaysZeroFilledAndBounded() {
        UUID da = UUID.randomUUID();
        when(repo.findByDaIdAndOperatingDateOrderByQueuePosition(da, date)).thenReturn(List.of());
        when(repo.paceByDa(null, date)).thenReturn(List.of());
        when(directory.contactsFor(List.of(da))).thenReturn(Map.of());
        // one in-range day with data + a later day the upper bound must exclude
        when(repo.paceByDaOverDays(eq(da), any(), eq(null))).thenReturn(List.of(
                new Day(date.minusDays(2), 4, 1),
                new Day(date.plusDays(1), 9, 9)));

        List<DaDetailResponse.DayStops> h = svc.daDetail(da, date, null).history();

        assertThat(h).hasSize(7);
        assertThat(h.get(0).date()).isEqualTo(date.minusDays(6));
        assertThat(h.get(6).date()).isEqualTo(date);
        assertThat(h).noneMatch(x -> x.date().isAfter(date));           // upper bound
        DaDetailResponse.DayStops filled = h.stream()
                .filter(x -> x.date().equals(date.minusDays(2))).findFirst().orElseThrow();
        assertThat(filled.done()).isEqualTo(4);
        assertThat(filled.failed()).isEqualTo(1);
        DaDetailResponse.DayStops empty = h.stream()
                .filter(x -> x.date().equals(date.minusDays(5))).findFirst().orElseThrow();
        assertThat(empty.done()).isZero();                              // zero-filled, not missing
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
