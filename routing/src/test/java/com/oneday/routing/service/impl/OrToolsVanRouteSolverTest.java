package com.oneday.routing.service.impl;

import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.model.VanRoute;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OR-Tools VRP solver. Skipped (assumption) where the native library is unavailable (e.g. CI) —
 * the {@link VanRouteSolverSelector} fallback path is covered separately, and CI exercises the
 * savings engine directly. Mirrors M3's {@code UnsatisfiedLinkError} handling.
 */
class OrToolsVanRouteSolverTest {

    private static boolean nativeAvailable;

    @BeforeAll
    static void loadNative() {
        try {
            com.google.ortools.Loader.loadNativeLibraries();
            nativeAvailable = true;
        } catch (Throwable t) {
            nativeAvailable = false;
        }
    }

    private OrToolsVanRouteSolver solver() {
        RoutingProperties props = new RoutingProperties();
        props.getSolver().setTimeLimitSeconds(3);
        return new OrToolsVanRouteSolver(props);
    }

    private static TravelMatrix lineMatrix(int[] deliver, int[] collect, int[] service) {
        long[][] s = {
                {0, 600, 1200, 1800},
                {600, 0, 600, 1200},
                {1200, 600, 0, 600},
                {1800, 1200, 600, 0}
        };
        return SolverFixtures.matrix(s, deliver, collect, service);
    }

    @Test
    void coversAllWithinCapacityAndSpan() {
        assumeTrue(nativeAvailable, "OR-Tools native library not available");

        TravelMatrix matrix = lineMatrix(
                new int[]{0, 2, 2, 2}, new int[]{0, 2, 2, 2}, new int[]{0, 60, 60, 60});

        SolveResult result = solver().solve(matrix, 2, 6, 180);

        assertThat(result.feasible()).isTrue();
        assertThat(result.solverType()).isEqualTo(RoutingSolverType.OR_TOOLS);
        assertThat(SolverFixtures.visitedNodeIndices(result)).containsExactlyInAnyOrder(1, 2, 3);
        for (VanRoute route : result.routes()) {
            assertThat(route.peakLoad()).isLessThanOrEqualTo(6);
            assertThat(route.spanSeconds()).isLessThanOrEqualTo(180L * 60);
        }
    }

    @Test
    void capacityNeverExceededAtAnyRoutePoint() {
        assumeTrue(nativeAvailable, "OR-Tools native library not available");

        // High per-stop delivery forces small loops; the peak-load constraint must hold everywhere.
        TravelMatrix matrix = lineMatrix(
                new int[]{0, 4, 4, 4}, new int[]{0, 1, 1, 1}, new int[]{0, 60, 60, 60});

        SolveResult result = solver().solve(matrix, 3, 6, 180);

        assertThat(result.feasible()).isTrue();
        assertThat(SolverFixtures.visitedNodeIndices(result)).containsExactlyInAnyOrder(1, 2, 3);
        for (VanRoute route : result.routes()) {
            assertThat(route.peakLoad()).isLessThanOrEqualTo(6);
        }
    }
}
