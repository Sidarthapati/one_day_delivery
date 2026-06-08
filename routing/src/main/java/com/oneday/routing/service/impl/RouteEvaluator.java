package com.oneday.routing.service.impl;

import com.oneday.routing.service.model.RouteStop;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.model.VanRoute;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an ordered meeting-vertex sequence (hub-anchored at both ends) into a {@link VanRoute},
 * computing travel time, span (travel + per-stop service), and the VRPSPD peak instantaneous load
 * (M6-D-002). Shared by both solvers so capacity/span are scored identically regardless of engine.
 *
 * <p>Load model: the van leaves the hub carrying all its deliveries ({@code startLoad}); at each
 * stop it simultaneously drops {@code deliverQty} and takes on {@code collectQty}. Peak load is the
 * max of the start load and every after-stop load.
 */
final class RouteEvaluator {

    private RouteEvaluator() {}

    /** @param vertexSeq node indices of the meeting vertices in visit order (depot 0 excluded). */
    static VanRoute evaluate(TravelMatrix matrix, int vanIndex, List<Integer> vertexSeq) {
        List<RoutingNode> nodes = matrix.nodes();

        int startLoad = 0;
        for (int node : vertexSeq) {
            startLoad += nodes.get(node).deliverQty();
        }

        List<RouteStop> stops = new ArrayList<>(vertexSeq.size());
        long travelSeconds = 0;
        long elapsed = 0;       // time from hub departure, includes service dwells
        int load = startLoad;
        int peak = startLoad;
        int prev = 0;           // hub depot

        for (int node : vertexSeq) {
            long arc = matrix.travel(prev, node);
            travelSeconds += arc;
            elapsed += arc;
            long arrival = elapsed;

            RoutingNode n = nodes.get(node);
            load = load - n.deliverQty() + n.collectQty();
            peak = Math.max(peak, load);

            elapsed += n.serviceTimeSeconds();
            stops.add(new RouteStop(node, n.refId(), arrival, load));
            prev = node;
        }

        long returnArc = matrix.travel(prev, 0);
        travelSeconds += returnArc;
        elapsed += returnArc;

        return new VanRoute(vanIndex, stops, travelSeconds, elapsed, peak);
    }
}
