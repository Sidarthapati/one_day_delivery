package com.oneday.dispatch.service.impl;

/**
 * Great-circle (haversine) distance helpers shared by the dispatch service implementations
 * (cron-vertex proximity in {@code DaStatusServiceImpl}, travel-time estimates in
 * {@code CronFeasibilityServiceImpl}). Package-private — the engine math, not a public API.
 */
final class GeoDistance {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private GeoDistance() {}

    /** Haversine great-circle distance in metres. */
    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Haversine great-circle distance in kilometres. */
    static double km(double lat1, double lon1, double lat2, double lon2) {
        return meters(lat1, lon1, lat2, lon2) / 1000.0;
    }
}
