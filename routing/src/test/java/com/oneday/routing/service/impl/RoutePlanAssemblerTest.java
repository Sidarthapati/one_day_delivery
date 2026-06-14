package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.service.model.MeetingPlan;
import com.oneday.routing.service.model.MeetingVertex;
import com.oneday.routing.service.model.RouteStop;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.VanRoute;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Periodise + derive (§7.4–7.5): n_loops, wall-clock ETAs, C6 spacing, per-DA meeting times. */
class RoutePlanAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int WINDOW_START_HOUR = 7;
    private static final int WINDOW_MINUTES = 13 * 60;   // 07:00–20:00
    private static final int DWELL_MINUTES = 5;
    private static final int CRON_FREEZE = 30;

    private final UUID cityId = UUID.randomUUID();
    private final UUID vertexA = UUID.randomUUID();
    private final UUID vertexB = UUID.randomUUID();
    private final UUID daA = UUID.randomUUID();
    private final UUID daB = UUID.randomUUID();

    /** Hub + A (arr 10min) + B (arr 60min); loop span 120min. */
    private SolveResult singleVanTwoStops() {
        RouteStop stopA = new RouteStop(1, vertexA, 600, 2);
        RouteStop stopB = new RouteStop(2, vertexB, 3600, 4);
        VanRoute route = new VanRoute(0, List.of(stopA, stopB), 7200, 7200, 9);
        return new SolveResult(List.of(route), RoutingSolverType.OR_TOOLS, true);
    }

    /** Daily {deliver, collect} per vertex; split by the van's 6 loops → A (5,3), B (4,2) per loop. */
    private Map<UUID, double[]> dailyByVertex() {
        return Map.of(vertexA, new double[]{30, 18}, vertexB, new double[]{24, 12});
    }

    private MeetingPlan plan() {
        return new MeetingPlan(
                List.of(new MeetingVertex(vertexA, 12.95, 77.6), new MeetingVertex(vertexB, 13.0, 77.7)),
                Map.of(vertexA, List.of(daA), vertexB, List.of(daB)),
                Map.of(daA, vertexA, daB, vertexB));
    }

    private RoutePlanAssembler.Assembly assemble() {
        UUID vanId = UUID.randomUUID();
        return RoutePlanAssembler.assemble(UUID.randomUUID(), cityId, LocalDate.of(2026, 6, 9),
                singleVanTwoStops(), plan(), dailyByVertex(),
                WINDOW_START_HOUR, WINDOW_MINUTES, DWELL_MINUTES, CRON_FREEZE, MAPPER, idx -> vanId);
    }

    @Test
    void computesNLoopsFromCadenceAndCycle() {
        RoutePlanAssembler.Assembly a = assemble();
        assertThat(a.realisedCycleMinutes()).isEqualTo(120);   // 7200s
        // cadence = max(120, 30) = 120; floor(780 / 120) = 6
        assertThat(a.nLoops()).isEqualTo(6);
        assertThat(a.stops()).hasSize(6 * 2);                  // 6 loops × 2 stops
    }

    @Test
    void stampsMonotonicWallClockEtas() {
        List<RoutePlanStop> stops = assemble().stops();

        // Loop 0: A at 07:10, B at 08:00.
        RoutePlanStop loop0A = stopOf(stops, 0, vertexA);
        RoutePlanStop loop0B = stopOf(stops, 0, vertexB);
        assertThat(loop0A.getPlannedArrival()).isEqualTo(LocalTime.of(7, 10));
        assertThat(loop0B.getPlannedArrival()).isEqualTo(LocalTime.of(8, 0));
        assertThat(loop0A.getPlannedArrival()).isBefore(loop0B.getPlannedArrival());
        assertThat(loop0A.getPlannedDeparture()).isEqualTo(LocalTime.of(7, 15)); // +dwell

        // Loop 1 is one cadence (120m) later than loop 0.
        RoutePlanStop loop1A = stopOf(stops, 1, vertexA);
        assertThat(loop1A.getPlannedArrival()).isEqualTo(LocalTime.of(9, 10));

        // Per-loop quantities = daily split by the van's 6 loops; load walked from start load 9 (=5+4):
        // after A → 9 − 5 + 3 = 7.
        assertThat(loop0A.getDeliverQty()).isEqualTo(5);
        assertThat(loop0A.getCollectQty()).isEqualTo(3);
        assertThat(loop0A.getLoadAfter()).isEqualTo(7);
    }

    @Test
    void derivesPerDaMeetingTimesSpacedByCadence() throws Exception {
        RoutePlanAssembler.Assembly a = assemble();
        assertThat(a.crons()).hasSize(2);

        DaCronSchedule cronA = a.crons().stream().filter(c -> c.getDaId().equals(daA)).findFirst().orElseThrow();
        assertThat(cronA.getHexVertexId()).isEqualTo(vertexA);

        List<String> times = MAPPER.readValue(cronA.getMeetingTimes(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(times).containsExactly("07:10", "09:10", "11:10", "13:10", "15:10", "17:10");

        // C6: consecutive meeting times are ≥ CRON_FREEZE minutes apart.
        for (int i = 1; i < times.size(); i++) {
            long gap = java.time.Duration.between(LocalTime.parse(times.get(i - 1)), LocalTime.parse(times.get(i))).toMinutes();
            assertThat(gap).isGreaterThanOrEqualTo(CRON_FREEZE);
        }
    }

    @Test
    void emptySolveYieldsEmptyAssembly() {
        RoutePlanAssembler.Assembly a = RoutePlanAssembler.assemble(UUID.randomUUID(), cityId, LocalDate.now(),
                new SolveResult(List.of(), RoutingSolverType.OR_TOOLS, true), plan(), dailyByVertex(),
                WINDOW_START_HOUR, WINDOW_MINUTES, DWELL_MINUTES, CRON_FREEZE, MAPPER, idx -> UUID.randomUUID());
        assertThat(a.nLoops()).isZero();
        assertThat(a.stops()).isEmpty();
        assertThat(a.crons()).isEmpty();
    }

    private static RoutePlanStop stopOf(List<RoutePlanStop> stops, int loop, UUID vertexId) {
        return stops.stream()
                .filter(s -> s.getLoopIndex() == loop && vertexId.equals(s.getHexVertexId()))
                .findFirst().orElseThrow();
    }
}
