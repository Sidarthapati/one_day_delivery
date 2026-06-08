package com.oneday.routing.service.impl;

import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.RouteStop;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.model.VanRoute;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared builders + assertions for the solver tests. Node 0 is the hub; 1..n-1 are vertices. */
final class SolverFixtures {

    private SolverFixtures() {}

    static TravelMatrix matrix(long[][] seconds, int[] deliver, int[] collect, int[] service) {
        int n = seconds.length;
        List<RoutingNode> nodes = new ArrayList<>(n);
        nodes.add(RoutingNode.hub(UUID.randomUUID(), 0, 0));
        for (int i = 1; i < n; i++) {
            nodes.add(new RoutingNode(i, StopNodeKind.MEETING_VERTEX, UUID.randomUUID(),
                    i, i, deliver[i], collect[i], service[i]));
        }
        return new TravelMatrix(nodes, seconds);
    }

    /** Every vertex (1..n-1) is visited exactly once across all routes. */
    static Set<Integer> visitedNodeIndices(SolveResult result) {
        Set<Integer> visited = new HashSet<>();
        for (VanRoute route : result.routes()) {
            for (RouteStop stop : route.stops()) {
                visited.add(stop.nodeIndex());
            }
        }
        return visited;
    }
}
