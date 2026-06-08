package com.oneday.routing.batch;

import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanSource;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.service.RoutePlanningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NightlyRoutePlanJobTest {

    @Mock CityFleetConfigRepository fleetConfigRepository;
    @Mock RoutePlanningService routePlanningService;
    @Mock RoutePlanRepository routePlanRepository;
    @Mock RoutePlanStopRepository routePlanStopRepository;
    @Mock DaCronScheduleRepository daCronScheduleRepository;

    // Fixed at 2026-06-09 12:00 IST → today=2026-06-09, tomorrow=2026-06-10.
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-09T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
    private final LocalDate today = LocalDate.of(2026, 6, 9);
    private final LocalDate tomorrow = LocalDate.of(2026, 6, 10);

    NightlyRoutePlanJob job;

    @BeforeEach
    void setUp() {
        job = new NightlyRoutePlanJob(fleetConfigRepository, routePlanningService,
                routePlanRepository, routePlanStopRepository, daCronScheduleRepository, clock);
    }

    private CityFleetConfig fleet(UUID cityId) {
        return CityFleetConfig.builder().cityId(cityId).build();
    }

    @Test
    void run_solvesTomorrowForEveryConfiguredCity() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID();
        when(fleetConfigRepository.findAll()).thenReturn(List.of(fleet(c1), fleet(c2)));

        job.run();

        verify(routePlanningService).plan(c1, tomorrow);
        verify(routePlanningService).plan(c2, tomorrow);
        verify(routePlanningService, times(2)).plan(any(), eq(tomorrow));
    }

    @Test
    void applyFallbackIfNeeded_unapproved_copiesYesterdaysApprovedPlanForward() {
        UUID cityId = UUID.randomUUID();
        UUID yId = UUID.randomUUID();
        when(fleetConfigRepository.findAll()).thenReturn(List.of(fleet(cityId)));
        // today: no APPROVED
        when(routePlanRepository.findByCityIdAndValidForDateAndStatus(cityId, today, RoutePlanStatus.APPROVED))
                .thenReturn(List.of());
        // yesterday: APPROVED present
        RoutePlan yesterday = RoutePlan.builder().id(yId).cityId(cityId)
                .validForDate(today.minusDays(1)).status(RoutePlanStatus.APPROVED)
                .source(RoutePlanSource.NIGHTLY).solverType(RoutingSolverType.OR_TOOLS)
                .revision(1).vansUsed(2).nLoops(4).build();
        when(routePlanRepository.findByCityIdAndValidForDateAndStatus(cityId, today.minusDays(1), RoutePlanStatus.APPROVED))
                .thenReturn(List.of(yesterday));
        when(routePlanStopRepository.findByRoutePlanId(yId)).thenReturn(List.of(
                RoutePlanStop.builder().routePlanId(yId).vanId(UUID.randomUUID()).loopIndex(0).stopSeq(1)
                        .nodeKind(StopNodeKind.MEETING_VERTEX).hexVertexId(UUID.randomUUID())
                        .plannedArrival(LocalTime.of(7, 30)).plannedDeparture(LocalTime.of(7, 35)).build()));
        when(daCronScheduleRepository.findByRoutePlanId(yId)).thenReturn(List.of(
                DaCronSchedule.builder().routePlanId(yId).daId(UUID.randomUUID()).hexVertexId(UUID.randomUUID())
                        .meetingTimes("[\"07:30\"]").cityId(cityId).validDate(today.minusDays(1)).build()));

        job.applyFallbackIfNeeded();

        ArgumentCaptor<RoutePlan> planCaptor = ArgumentCaptor.forClass(RoutePlan.class);
        verify(routePlanRepository).save(planCaptor.capture());
        RoutePlan fallback = planCaptor.getValue();
        assertThat(fallback.getValidForDate()).isEqualTo(today);
        assertThat(fallback.getStatus()).isEqualTo(RoutePlanStatus.APPROVED);
        assertThat(fallback.getSource()).isEqualTo(RoutePlanSource.FALLBACK);
        assertThat(fallback.getSupersedesPlanId()).isEqualTo(yId);
        verify(routePlanStopRepository).saveAll(anyList());
        verify(daCronScheduleRepository).saveAll(anyList());
    }

    @Test
    void applyFallbackIfNeeded_alreadyApproved_doesNothing() {
        UUID cityId = UUID.randomUUID();
        when(fleetConfigRepository.findAll()).thenReturn(List.of(fleet(cityId)));
        when(routePlanRepository.findByCityIdAndValidForDateAndStatus(cityId, today, RoutePlanStatus.APPROVED))
                .thenReturn(List.of(RoutePlan.builder().id(UUID.randomUUID()).cityId(cityId)
                        .validForDate(today).status(RoutePlanStatus.APPROVED).source(RoutePlanSource.NIGHTLY)
                        .solverType(RoutingSolverType.OR_TOOLS).build()));

        job.applyFallbackIfNeeded();

        verify(routePlanRepository, never()).save(any());
        verify(routePlanStopRepository, never()).saveAll(anyList());
    }
}
