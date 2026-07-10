package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.CronFeasibilityService;
import com.oneday.dispatch.service.FeasibilityRequest;
import com.oneday.dispatch.service.FeasibilityResult;
import com.oneday.dispatch.service.FeasibilityStop;
import com.oneday.dispatch.service.OsrmRoutingPort;
import com.oneday.dispatch.service.model.LatLon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exhaustive unit tests for the cron-feasibility engine. Geometry uses points spaced along a
 * meridian: {@link #north(double)} returns a coordinate ~{@code km} kilometres north of the origin
 * (1° latitude ≈ 111.195 km). With the default config (roadFactor 1.4, 25 km/h) a 5 km leg costs
 * exactly {@code 5 × 1.4 ÷ 25 × 3600 = 1008 s}, which several assertions rely on.
 */
class CronFeasibilityServiceImplTest {

    private static final LatLon ORIGIN = new LatLon(0, 0);
    private static final Instant BASE = Instant.parse("2026-06-20T06:00:00Z");
    /** Seconds the haversine engine charges for one 5 km leg under default config. */
    private static final long LEG_5KM = 1008;

    private OsrmRoutingPort osrm;
    private CronFeasibilityService service;

    @BeforeEach
    void setUp() {
        osrm = mock(OsrmRoutingPort.class);
        service = new CronFeasibilityServiceImpl(new DispatchProperties(), osrm);
    }

    @Test
    void travelFormulaConvertsToSeconds_5kmEquals1008() {
        // Empty queue, task and cron co-located 5 km out: arrival == one 5 km leg == 1008 s.
        // Slack 4008 keeps it well clear of the OSRM confirm window, so this is a pure fast-path check.
        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(), task(5), north(5), 4008));

        assertThat(r.feasible()).isTrue();
        assertThat(r.bestInsertionIndex()).isZero();
        assertThat(r.usedOsrm()).isFalse();
        assertThat(r.cronSlackSeconds()).isEqualTo(4008 - LEG_5KM);   // 3000
        verifyNoInteractions(osrm);
    }

    @Test
    void emptyQueueInfeasibleWhenTaskTooFar() {
        // 20 km out → 4032 s, slack only 1000 s, and far enough from the cutoff to skip OSRM.
        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(), task(20), north(20), 1000));

        assertThat(r.feasible()).isFalse();
        assertThat(r.cronSlackSeconds()).isNegative();
        assertThat(r.usedOsrm()).isFalse();
        verifyNoInteractions(osrm);
    }

    @ParameterizedTest(name = "new task {0} km out → cheapest insertion at index {1}")
    @CsvSource({"1, 0", "19, 1"})
    void cheapestInsertionPicksMinimumDetour(double newTaskKm, int expectedIndex) {
        // Existing stop 10 km out, cron 20 km out. A task near the origin splices cheapest before the
        // existing stop (index 0); one near the cron splices cheapest after it (index 1).
        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(stop(10)), task(newTaskKm), north(20), 6000));

        assertThat(r.feasible()).isTrue();
        assertThat(r.bestInsertionIndex()).isEqualTo(expectedIndex);
        assertThat(r.extraTravelSeconds()).isZero();   // colinear inserts add no detour
        verifyNoInteractions(osrm);
    }

    @Test
    void allPositionsInfeasibleReturnsFalse() {
        // Existing stop 30 km out, cron 60 km out, new task 45 km out: the route alone busts the cutoff.
        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(stop(30)), task(45), north(60), 1000));

        assertThat(r.feasible()).isFalse();
        verifyNoInteractions(osrm);
    }

    @Test
    void borderlineOsrmOverridesFastPathToInfeasible() {
        // Fast path: arrival 1008 ≤ slack 1100 (feasible) but within the 1200 s confirm window.
        // OSRM says the real drive is 1200 s → 1200 > 1100 → the decision flips to infeasible.
        when(osrm.routeDurationSeconds(anyList())).thenReturn(OptionalLong.of(1200));

        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(), task(5), north(5), 1100));

        assertThat(r.feasible()).isFalse();
        assertThat(r.usedOsrm()).isTrue();
        verify(osrm, times(1)).routeDurationSeconds(anyList());
    }

    @Test
    void borderlineOsrmOverridesFastPathToFeasible() {
        // Fast path: arrival 1008 > slack 950 (infeasible) but borderline. OSRM says 950 s exactly,
        // so arrival == cutoff → feasible (the bound is inclusive).
        when(osrm.routeDurationSeconds(anyList())).thenReturn(OptionalLong.of(950));

        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(), task(5), north(5), 950));

        assertThat(r.feasible()).isTrue();
        assertThat(r.usedOsrm()).isTrue();
        assertThat(r.cronSlackSeconds()).isZero();
        verify(osrm, times(1)).routeDurationSeconds(anyList());
    }

    @Test
    void borderlineOsrmOpenFallsBackToConservativeHaversine() {
        // OSRM unavailable on a borderline check → recompute with roadFactor × 1.2 (1.68). The 5 km
        // leg now costs 1210 s > slack 1100, so the conservative estimate rejects what the fast path
        // would have accepted. usedOsrm stays false.
        when(osrm.routeDurationSeconds(anyList())).thenReturn(OptionalLong.empty());

        FeasibilityResult r = service.checkFeasibility(
                req(ORIGIN, List.of(), task(5), north(5), 1100));

        assertThat(r.feasible()).isFalse();
        assertThat(r.usedOsrm()).isFalse();
        verify(osrm, times(1)).routeDurationSeconds(anyList());
    }

    @Test
    void inProgressEstimateShrinksSlackViaCurrentPositionAndTime() {
        // §8.4: an IN_PROGRESS task is modelled as currentPosition = that stop, currentTime = its ETA.
        // The DA is already 4 km out with only 1008 s of slack; reaching the cron 20 km out (≈3226 s)
        // busts it. Anchoring at the in-progress position is what makes this infeasible.
        FeasibilityRequest fromInProgress = new FeasibilityRequest(
                north(4), BASE, List.of(), task(4), north(20), BASE.plusSeconds(1008));

        FeasibilityResult r = service.checkFeasibility(fromInProgress);

        assertThat(r.bestInsertionIndex()).isZero();
        assertThat(r.feasible()).isFalse();
        verifyNoInteractions(osrm);
    }

    @Test
    void engineIsStatelessUnderConcurrentCalls() throws Exception {
        // Pure function: the same request fired from many threads must yield identical results.
        FeasibilityRequest request = req(ORIGIN, List.of(stop(10)), task(1), north(20), 6000);
        FeasibilityResult expected = service.checkFeasibility(request);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<FeasibilityResult>> jobs = IntStream.range(0, 500)
                    .mapToObj(i -> (Callable<FeasibilityResult>) () -> service.checkFeasibility(request))
                    .collect(Collectors.toList());
            List<Future<FeasibilityResult>> results = pool.invokeAll(jobs);
            for (Future<FeasibilityResult> f : results) {
                assertThat(f.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /** A coordinate ~{@code km} kilometres north of the origin. */
    private static LatLon north(double km) {
        return new LatLon(km / 111.195, 0);
    }

    /** An existing queued stop {@code km} out with zero service time (isolates travel math). */
    private static FeasibilityStop stop(double km) {
        return new FeasibilityStop(north(km), 0);
    }

    /** The incoming task {@code km} out with zero service time. */
    private static FeasibilityStop task(double km) {
        return new FeasibilityStop(north(km), 0);
    }

    private static FeasibilityRequest req(LatLon current, List<FeasibilityStop> existing,
                                          FeasibilityStop newTask, LatLon cron, long slackSeconds) {
        return new FeasibilityRequest(current, BASE, existing, newTask, cron,
                BASE.plusSeconds(slackSeconds));
    }
}
