package com.oneday.routing.service.model;

import com.oneday.routing.domain.StopNodeKind;

import java.util.UUID;

/**
 * A node in the VRP graph (§5): the hub depot (index 0) or a meeting vertex. {@code deliverQty} /
 * {@code collectQty} are the packets dropped / picked up here for one loop (forecast at plan-time);
 * the van's load along the route is {@code totalDeliveries − delivered + collected}, and must stay
 * ≤ capacity everywhere (VRPSPD peak-load, M6-D-002). {@code refId} is the hub or vertex id.
 */
public record RoutingNode(
        int index,
        StopNodeKind kind,
        UUID refId,
        double lat,
        double lon,
        int deliverQty,
        int collectQty,
        int serviceTimeSeconds
) {
    public static RoutingNode hub(UUID hubId, double lat, double lon) {
        return hub(hubId, lat, lon, 0);
    }

    /** Hub with a turnaround service time (unload + reload) charged on each loop return. */
    public static RoutingNode hub(UUID hubId, double lat, double lon, int turnaroundSeconds) {
        return new RoutingNode(0, StopNodeKind.HUB, hubId, lat, lon, 0, 0, turnaroundSeconds);
    }
}
