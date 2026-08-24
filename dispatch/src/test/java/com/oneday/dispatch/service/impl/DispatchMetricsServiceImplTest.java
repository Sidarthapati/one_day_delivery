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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DispatchMetricsServiceImplTest {

    private final DispatchQueueRepository repo = mock(DispatchQueueRepository.class);
    private final com.oneday.common.port.DaDirectoryPort directory =
            mock(com.oneday.common.port.DaDirectoryPort.class);
    private final com.oneday.common.port.ShipmentSlaPort slaPort =
            mock(com.oneday.common.port.ShipmentSlaPort.class);
    private final com.oneday.common.port.ShipmentRefPort refPort =
            mock(com.oneday.common.port.ShipmentRefPort.class);
    private final com.oneday.dispatch.service.DaStatusService daStatus =
            mock(com.oneday.dispatch.service.DaStatusService.class);
    private final DispatchMetricsServiceImpl svc =
            new DispatchMetricsServiceImpl(repo, directory, slaPort, refPort, daStatus);
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

    private record Score(UUID daId, long done, long failed, long onTime, long pending, Instant firstAssigned)
            implements com.oneday.dispatch.repository.DaScorecardRow {
        @Override public UUID getDaId() { return daId; }
        @Override public long getDone() { return done; }
        @Override public long getFailed() { return failed; }
        @Override public long getOnTime() { return onTime; }
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
    void daDetailUsesRealSlaColourOverEtaHeuristic() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        Instant now = Instant.now();
        DispatchQueue breaching = task(city, TaskStatus.IN_PROGRESS, now.plusSeconds(3600)); // ETA on-track → AMBER
        DispatchQueue noSla = task(city, TaskStatus.QUEUED, now.plusSeconds(3600));          // ETA on-track → GREEN
        when(repo.findByDaIdAndOperatingDateOrderByQueuePosition(da, date))
                .thenReturn(List.of(breaching, noSla));
        when(repo.paceByDa(null, date)).thenReturn(List.of());
        when(repo.paceByDaOverDays(eq(da), any(), eq(null))).thenReturn(List.of());
        when(directory.contactsFor(List.of(da))).thenReturn(Map.of());
        // Only the in-progress task has an SLA row, and it is BREACHED → RED, overriding the on-track ETA.
        when(slaPort.slaFor(any())).thenReturn(Map.of(breaching.getShipmentId(),
                new com.oneday.common.port.ShipmentSlaPort.SlaStatus(
                        com.oneday.common.domain.enums.SlaState.BREACHED, true, 22, now.plusSeconds(600))));
        when(refPort.refsFor(any())).thenReturn(Map.of(breaching.getShipmentId(), "1DD-BLR-1"));

        DaDetailResponse d = svc.daDetail(da, date, null);

        // BREACHED in-progress sorts RED (ahead of the no-SLA GREEN task, which falls back to the heuristic).
        assertThat(d.tasks()).extracting(DaDetailResponse.DaTaskItem::urgency)
                .containsExactly("RED", "GREEN");
        DaDetailResponse.DaTaskItem top = d.tasks().get(0);
        assertThat(top.shipmentRef()).isEqualTo("1DD-BLR-1");
        assertThat(top.urgencyMinutes()).isEqualTo(22);
        assertThat(top.actByAt()).isNotNull();
    }

    @Test
    void daTrailGuardsCityAndDelegates() {
        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        UUID otherCity = UUID.randomUUID();
        when(repo.findByDaIdAndOperatingDateOrderByQueuePosition(da, date))
                .thenReturn(List.of(task(city, TaskStatus.IN_PROGRESS, null)));
        when(daStatus.listTrack(eq(da), any(), any()))
                .thenReturn(List.of(new com.oneday.dispatch.service.GpsFixView(12.9, 77.6, Instant.now())));

        // A manager scoped to another city can't see this DA's trail.
        assertThatThrownBy(() -> svc.daTrail(da, date, otherCity))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        // In-scope, and admin (null scope), both delegate to listTrack.
        assertThat(svc.daTrail(da, date, city)).hasSize(1);
        assertThat(svc.daTrail(da, date, null)).hasSize(1);
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

    @Test
    void scorecardsComputeSuccessOnTimeAndSortBusiestFirst() {
        Instant now = Instant.now();
        // stops/hr is clock-bounded at the IST end of the operating day, so "today" must be today in
        // IST for that end to still be in the future (else a UTC-evening CI run clamps the 4h window).
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        UUID busy = UUID.randomUUID(), idle = UUID.randomUUID();
        when(repo.scorecardByDa(null, today)).thenReturn(List.of(
                // 8 done / 2 failed → success 0.8; 6 on-time of 8 → 0.75; 8 stops over 4h → 2/hr
                new Score(busy, 8, 2, 6, 1, now.minus(4, ChronoUnit.HOURS)),
                // no attempts → both pcts null (not 0), stays visible
                new Score(idle, 0, 0, 0, 3, now.minus(1, ChronoUnit.HOURS))));
        when(directory.contactsFor(List.of(busy, idle)))
                .thenReturn(Map.of(busy, new DaContact("Ravi", "+9111")));

        List<com.oneday.dispatch.dto.response.DaScorecard> cards = svc.scorecards(today, null);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).daId()).isEqualTo(busy);              // most done sorts first
        assertThat(cards.get(0).daName()).isEqualTo("Ravi");
        assertThat(cards.get(0).attemptSuccessPct()).isEqualTo(0.8);
        assertThat(cards.get(0).onTimePct()).isEqualTo(0.75);
        assertThat(cards.get(0).stopsPerHour()).isCloseTo(2.0, within(0.05));
        assertThat(cards.get(1).attemptSuccessPct()).isNull();        // no attempts → null, not 0
        assertThat(cards.get(1).onTimePct()).isNull();
    }

    @Test
    void scorecardsBoundStopsPerHourToPastOperatingDate() {
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        LocalDate pastDate = LocalDate.now(ist).minusDays(2);
        UUID da = UUID.randomUUID();
        // 8 stops, first assigned 09:00 IST on that past date → clock caps at that day's 00:00 next-day
        // (~15h), so ~0.53/hr; a now-based clock (2 days later) would read ~0.15/hr.
        Instant firstAssigned = pastDate.atStartOfDay(ist).plusHours(9).toInstant();
        when(repo.scorecardByDa(null, pastDate)).thenReturn(List.of(new Score(da, 8, 0, 8, 0, firstAssigned)));
        when(directory.contactsFor(List.of(da))).thenReturn(Map.of());

        double perHour = svc.scorecards(pastDate, null).get(0).stopsPerHour();

        assertThat(perHour).isGreaterThan(0.4);            // day-bounded, not spread across 2 days
        assertThat(perHour).isCloseTo(8.0 / 15.0, within(0.05));
    }
}
