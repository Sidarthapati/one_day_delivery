package com.oneday.routing.service.model;

import java.util.List;

/**
 * One van's solved loop: ordered stops (hub → vertices → hub), total travel + service time, and
 * the peak instantaneous load reached (the VRPSPD binding quantity, M6-D-002).
 */
public record VanRoute(
        int vanIndex,
        List<RouteStop> stops,
        long totalTravelSeconds,
        long spanSeconds,
        int peakLoad
) {}
