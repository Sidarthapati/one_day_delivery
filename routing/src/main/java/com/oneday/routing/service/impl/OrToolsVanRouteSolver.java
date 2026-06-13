package com.oneday.routing.service.impl;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.IntVar;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.service.VanRouteSolver;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.model.VanRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OR-Tools {@code RoutingModel} VRP solver (M6-D-006, §7.3). Models the loop as:
 * <ul>
 *   <li><b>Arc cost / Time</b> — travel + per-stop service; route span ≤ {@code cycleMax} (VRPTW).</li>
 *   <li><b>Capacity (VRPSPD peak-load, M6-D-002)</b> — a "Delivered" dimension whose per-vehicle end
 *       cumul = the deliveries it carries, pinned to the start of a "Load" dimension that then nets
 *       {@code collect − deliver} at each stop. Bounding Load ∈ [0, capacity] everywhere makes the
 *       peak instantaneous load (not the net) the binding constraint.</li>
 * </ul>
 * The native library is loaded lazily on first solve so a missing lib does not break bean creation
 * — {@code VanRouteSolverSelector} catches the failure and falls back to Clarke–Wright savings
 * (mirrors M3's CP-SAT → BFS fallback).
 */
@Component
class OrToolsVanRouteSolver implements VanRouteSolver {

    private static final Logger log = LoggerFactory.getLogger(OrToolsVanRouteSolver.class);

    private final RoutingProperties properties;
    private volatile boolean nativeLoaded = false;

    OrToolsVanRouteSolver(RoutingProperties properties) {
        this.properties = properties;
    }

    private synchronized void ensureNativeLoaded() {
        if (!nativeLoaded) {
            Loader.loadNativeLibraries();
            nativeLoaded = true;
        }
    }

    @Override
    public SolveResult solve(TravelMatrix matrix, int vansAvailable, int capacityPackets, int cycleMaxMinutes) {
        ensureNativeLoaded();

        int n = matrix.size();
        // Only the hub (no meeting vertices) → nothing to route; trivially feasible, no loops.
        if (n <= 1 || vansAvailable <= 0) {
            return new SolveResult(List.of(), RoutingSolverType.OR_TOOLS, true);
        }

        List<RoutingNode> nodes = matrix.nodes();
        long cycleMaxSeconds = (long) cycleMaxMinutes * 60;
        int totalDeliveries = nodes.stream().mapToInt(RoutingNode::deliverQty).sum();

        RoutingIndexManager manager = new RoutingIndexManager(n, vansAvailable, 0);
        RoutingModel routing = new RoutingModel(manager);

        // Arc cost = travel time (cost-floor-weighted when M2 lands, M6-D-010).
        int travelCb = routing.registerTransitCallback((long fromIndex, long toIndex) ->
                matrix.travel(manager.indexToNode(fromIndex), manager.indexToNode(toIndex)));
        routing.setArcCostEvaluatorOfAllVehicles(travelCb);

        // Time dimension: travel + service at the destination; span (= end cumul) ≤ cycleMax.
        int timeCb = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int to = manager.indexToNode(toIndex);
            return matrix.travel(manager.indexToNode(fromIndex), to) + nodes.get(to).serviceTimeSeconds();
        });
        routing.addDimension(timeCb, 0, cycleMaxSeconds, true, "Time");
        // Balance the work across the fleet: penalise the longest route so the solver spreads stops
        // over the available vans (minimise makespan) instead of consolidating onto the fewest vans
        // — without this, plentiful capacity + a loose cycle bound let one van swallow the whole city.
        RoutingDimension timeDim = routing.getDimensionOrDie("Time");
        timeDim.setGlobalSpanCostCoefficient(100);

        // "Delivered": per-vehicle end cumul = total deliveries it carries.
        int deliveredCb = routing.registerUnaryTransitCallback((long fromIndex) ->
                nodes.get(manager.indexToNode(fromIndex)).deliverQty());
        routing.addDimension(deliveredCb, 0, Math.max(totalDeliveries, 1), true, "Delivered");
        RoutingDimension deliveredDim = routing.getDimensionOrDie("Delivered");

        // "Load": net collect−deliver per stop; bound ∈ [0, capacity] everywhere (peak-load).
        int loadCb = routing.registerUnaryTransitCallback((long fromIndex) -> {
            RoutingNode node = nodes.get(manager.indexToNode(fromIndex));
            return (long) node.collectQty() - node.deliverQty();
        });
        routing.addDimension(loadCb, 0, capacityPackets, false, "Load");
        RoutingDimension loadDim = routing.getDimensionOrDie("Load");

        // Pin each van's starting load to the deliveries it ends up carrying.
        var solver = routing.solver();
        for (int v = 0; v < vansAvailable; v++) {
            IntVar loadStart = loadDim.cumulVar(routing.start(v));
            IntVar deliveredEnd = deliveredDim.cumulVar(routing.end(v));
            solver.addConstraint(solver.makeEquality(loadStart, deliveredEnd));
        }

        RoutingSearchParameters params = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(Duration.newBuilder().setSeconds(properties.getSolver().getTimeLimitSeconds()).build())
                .build();

        Assignment solution = routing.solveWithParameters(params);
        if (solution == null) {
            log.info("OR-Tools found no feasible routing for {} vans, capacity {}, cycleMax {}min over {} nodes",
                    vansAvailable, capacityPackets, cycleMaxMinutes, n);
            return new SolveResult(List.of(), RoutingSolverType.OR_TOOLS, false);
        }

        List<VanRoute> routes = new ArrayList<>();
        for (int v = 0; v < vansAvailable; v++) {
            List<Integer> seq = new ArrayList<>();
            long index = routing.start(v);
            while (!routing.isEnd(index)) {
                int node = manager.indexToNode(index);
                if (node != 0) seq.add(node);
                index = solution.value(routing.nextVar(index));
            }
            if (!seq.isEmpty()) {
                routes.add(RouteEvaluator.evaluate(matrix, v, seq));
            }
        }
        return new SolveResult(routes, RoutingSolverType.OR_TOOLS, true);
    }
}
