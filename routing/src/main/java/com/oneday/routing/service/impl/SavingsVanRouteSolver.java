package com.oneday.routing.service.impl;

import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.service.VanRouteSolver;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.model.VanRoute;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clarke–Wright savings VRP — the documented no-solver fallback (M6-D-006, §3), used when the
 * OR-Tools native library is absent (e.g. CI) or it errors out. Mirrors M3's BFS-fallback role:
 * a deterministic heuristic that respects the same capacity (peak-load) and cycle-span bounds via
 * {@link RouteEvaluator}, so its output is interchangeable with OR-Tools' for downstream stages.
 *
 * <p>Start with one route per vertex, then greedily merge endpoint pairs in descending savings
 * {@code s(i,j) = d(0,i) + d(0,j) − d(i,j)} while the merged loop stays within capacity and span.
 */
@Component
class SavingsVanRouteSolver implements VanRouteSolver {

    @Override
    public SolveResult solve(TravelMatrix matrix, int vansAvailable, int capacityPackets, int cycleMaxMinutes) {
        int n = matrix.size();
        if (n <= 1 || vansAvailable <= 0) {
            return new SolveResult(List.of(), RoutingSolverType.SAVINGS, true);
        }
        long cycleMaxSeconds = (long) cycleMaxMinutes * 60;

        // One route per meeting vertex (nodes 1..n-1; node 0 is the hub).
        List<List<Integer>> routes = new ArrayList<>();
        for (int node = 1; node < n; node++) {
            List<Integer> r = new ArrayList<>();
            r.add(node);
            if (!feasible(matrix, r, capacityPackets, cycleMaxSeconds)) {
                // A single vertex already breaches capacity/cycle — no routing can fix it.
                return new SolveResult(List.of(), RoutingSolverType.SAVINGS, false);
            }
            routes.add(r);
        }

        // Savings, descending. Merging the higher-saving endpoints first is the classic heuristic.
        List<long[]> savings = new ArrayList<>(); // {saving, i, j}
        for (int i = 1; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long s = matrix.travel(0, i) + matrix.travel(0, j) - matrix.travel(i, j);
                savings.add(new long[]{s, i, j});
            }
        }
        savings.sort((a, b) -> Long.compare(b[0], a[0]));

        for (long[] s : savings) {
            int i = (int) s[1];
            int j = (int) s[2];
            List<Integer> ri = routeContaining(routes, i);
            List<Integer> rj = routeContaining(routes, j);
            if (ri == null || rj == null || ri == rj) continue;

            // i must sit at an end of its route, j at an end of its; orient so the merge is ...i, j...
            boolean iEnd = ri.get(ri.size() - 1) == i;
            boolean iStart = ri.get(0) == i;
            boolean jStart = rj.get(0) == j;
            boolean jEnd = rj.get(rj.size() - 1) == j;
            if (!(iStart || iEnd) || !(jStart || jEnd)) continue;

            List<Integer> left = iEnd ? new ArrayList<>(ri) : reversed(ri);
            List<Integer> right = jStart ? new ArrayList<>(rj) : reversed(rj);
            List<Integer> merged = new ArrayList<>(left);
            merged.addAll(right);

            if (!feasible(matrix, merged, capacityPackets, cycleMaxSeconds)) continue;

            routes.remove(ri);
            routes.remove(rj);
            routes.add(merged);
        }

        if (routes.size() > vansAvailable) {
            return new SolveResult(List.of(), RoutingSolverType.SAVINGS, false);
        }

        List<VanRoute> vanRoutes = new ArrayList<>(routes.size());
        for (int v = 0; v < routes.size(); v++) {
            vanRoutes.add(RouteEvaluator.evaluate(matrix, v, routes.get(v)));
        }
        return new SolveResult(vanRoutes, RoutingSolverType.SAVINGS, true);
    }

    private static boolean feasible(TravelMatrix matrix, List<Integer> seq, int capacity, long cycleMaxSeconds) {
        VanRoute r = RouteEvaluator.evaluate(matrix, 0, seq);
        return r.peakLoad() <= capacity && r.spanSeconds() <= cycleMaxSeconds;
    }

    private static List<Integer> routeContaining(List<List<Integer>> routes, int node) {
        for (List<Integer> r : routes) {
            if (r.contains(node)) return r;
        }
        return null;
    }

    private static List<Integer> reversed(List<Integer> in) {
        List<Integer> out = new ArrayList<>(in);
        Collections.reverse(out);
        return out;
    }
}
