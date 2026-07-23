package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Live-tracking configuration.
 *
 * <pre>{@code
 * tracking:
 *   gps-stale-seconds: 180
 *   city-nodes:
 *     DEL: { hub-lat: 28.50, hub-lon: 77.15, airport-lat: 28.5562, airport-lon: 77.1000 }
 * }</pre>
 *
 * <p>{@code city-nodes} is keyed by IATA city code; {@link com.oneday.orders.tracking.CityNodeCatalog}
 * also accepts the full city name (DELHI → DEL) since older shipment rows stored either form.</p>
 */
@Component
@ConfigurationProperties(prefix = "tracking")
public class TrackingProperties {

    /** A fix older than this is treated as stale — the tracker shows the static node, not the dot. */
    private int gpsStaleSeconds = 180;

    /** Per-city hub + airport coordinates, keyed by IATA city code (DEL, BOM, BLR, HYD, MAA). */
    private Map<String, CityNode> cityNodes = new HashMap<>();

    public int getGpsStaleSeconds() {
        return gpsStaleSeconds;
    }

    public void setGpsStaleSeconds(int gpsStaleSeconds) {
        this.gpsStaleSeconds = gpsStaleSeconds;
    }

    public Map<String, CityNode> getCityNodes() {
        return cityNodes;
    }

    public void setCityNodes(Map<String, CityNode> cityNodes) {
        this.cityNodes = cityNodes;
    }

    /** Fixed hub + airport coordinates for one city. Approximate placeholders — confirm with ops. */
    public static class CityNode {
        private double hubLat;
        private double hubLon;
        private double airportLat;
        private double airportLon;

        public double getHubLat() { return hubLat; }
        public void setHubLat(double hubLat) { this.hubLat = hubLat; }

        public double getHubLon() { return hubLon; }
        public void setHubLon(double hubLon) { this.hubLon = hubLon; }

        public double getAirportLat() { return airportLat; }
        public void setAirportLat(double airportLat) { this.airportLat = airportLat; }

        public double getAirportLon() { return airportLon; }
        public void setAirportLon(double airportLon) { this.airportLon = airportLon; }
    }
}
