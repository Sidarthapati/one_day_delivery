package com.oneday.airline.config;

import java.util.Map;

/**
 * City-centre lat/lon for the 5 grid cities, used only to interpolate a simulated in-flight position
 * (§8) between origin and destination. Five fixed points don't warrant a dependency on {@code grid}
 * (which computes real H3 hex boundaries, not simple centroids) — hardcoded here instead.
 */
public final class HubCoordinates {

    private HubCoordinates() {}

    public record Coord(double lat, double lon) {
    }

    private static final Map<String, Coord> BY_HUB = Map.of(
            "DEL", new Coord(28.6139, 77.2090),
            "BOM", new Coord(19.0760, 72.8777),
            "BLR", new Coord(12.9716, 77.5946),
            "HYD", new Coord(17.3850, 78.4867),
            "MAA", new Coord(13.0827, 80.2707));

    public static Coord of(String hubCode) {
        Coord coord = BY_HUB.get(hubCode);
        if (coord == null) {
            throw new IllegalArgumentException("No coordinates known for hub: " + hubCode);
        }
        return coord;
    }
}
