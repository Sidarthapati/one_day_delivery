package com.oneday.dispatch.service.model;

/**
 * An immutable WGS84 coordinate (decimal degrees). The geometry primitive shared by the assignment
 * engine — DA position, task locations, and the cron meeting vertex are all {@code LatLon}s.
 */
public record LatLon(double lat, double lon) {

    public static LatLon of(double lat, double lon) {
        return new LatLon(lat, lon);
    }
}
