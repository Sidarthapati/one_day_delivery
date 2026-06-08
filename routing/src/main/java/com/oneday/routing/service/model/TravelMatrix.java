package com.oneday.routing.service.model;

import java.util.List;

/**
 * Travel times (seconds) between every pair of routing nodes over M6's own node set
 * {@code {hub} ∪ vertices (∪ airport)} (M6-D-009). {@code travelSeconds[i][j]} is node i → node j;
 * {@code nodes.get(i).index() == i}.
 */
public record TravelMatrix(List<RoutingNode> nodes, long[][] travelSeconds) {

    public int size() {
        return nodes.size();
    }

    public long travel(int from, int to) {
        return travelSeconds[from][to];
    }
}
