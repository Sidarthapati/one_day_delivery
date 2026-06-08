package com.oneday.routing.service.impl;

import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.model.VanRoute;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Clarke–Wright fallback: respects peak-load capacity (M6-D-002) and the cycle-span bound. */
class SavingsVanRouteSolverTest {

    private final SavingsVanRouteSolver solver = new SavingsVanRouteSolver();

    /** Hub + 3 vertices on a line: 0—1—2—3 at 600s hops. */
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
    void singleVanCoversAllWithinCapacityAndSpan() {
        TravelMatrix matrix = lineMatrix(
                new int[]{0, 2, 2, 2}, new int[]{0, 2, 2, 2}, new int[]{0, 60, 60, 60});

        SolveResult result = solver.solve(matrix, 2, 6, 180);

        assertThat(result.feasible()).isTrue();
        assertThat(result.solverType()).isEqualTo(RoutingSolverType.SAVINGS);
        assertThat(SolverFixtures.visitedNodeIndices(result)).containsExactlyInAnyOrder(1, 2, 3);
        for (VanRoute route : result.routes()) {
            assertThat(route.peakLoad()).isLessThanOrEqualTo(6);
            assertThat(route.spanSeconds()).isLessThanOrEqualTo(180L * 60);
        }
    }

    @Test
    void capacityForcesMultipleVansAndPeakNeverExceeded() {
        // Each vertex needs 4 delivered; capacity 6 ⇒ at most one vertex per van loop.
        TravelMatrix matrix = lineMatrix(
                new int[]{0, 4, 4, 4}, new int[]{0, 0, 0, 0}, new int[]{0, 60, 60, 60});

        SolveResult result = solver.solve(matrix, 3, 6, 180);

        assertThat(result.feasible()).isTrue();
        assertThat(result.routes()).hasSize(3);
        assertThat(SolverFixtures.visitedNodeIndices(result)).containsExactlyInAnyOrder(1, 2, 3);
        for (VanRoute route : result.routes()) {
            assertThat(route.peakLoad()).isLessThanOrEqualTo(6);
        }
    }

    @Test
    void infeasibleWhenNotEnoughVans() {
        // 3 vertices each needing a whole van, but only 1 available.
        TravelMatrix matrix = lineMatrix(
                new int[]{0, 4, 4, 4}, new int[]{0, 0, 0, 0}, new int[]{0, 60, 60, 60});

        SolveResult result = solver.solve(matrix, 1, 6, 180);

        assertThat(result.feasible()).isFalse();
    }
}
