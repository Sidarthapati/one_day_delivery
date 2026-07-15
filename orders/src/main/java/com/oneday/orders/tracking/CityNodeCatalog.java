package com.oneday.orders.tracking;

import com.oneday.orders.config.TrackingProperties;
import com.oneday.orders.config.TrackingProperties.CityNode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a shipment's city code to that city's fixed hub / airport coordinates. Config is keyed by
 * IATA code (DEL, BOM, …); this catalog also accepts the full city name (DELHI → DEL) and is
 * case-insensitive, because {@code shipments.origin_city} has historically stored either form.
 */
@Component
public class CityNodeCatalog {

    // Full city name → IATA code, so a row storing "DELHI" resolves to the DEL node.
    private static final Map<String, String> NAME_TO_IATA = Map.of(
            "DELHI", "DEL",
            "MUMBAI", "BOM",
            "BANGALORE", "BLR",
            "BENGALURU", "BLR",
            "HYDERABAD", "HYD",
            "CHENNAI", "MAA");

    private final TrackingProperties properties;

    CityNodeCatalog(TrackingProperties properties) {
        this.properties = properties;
    }

    private Optional<CityNode> node(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return Optional.empty();
        }
        String key = cityCode.trim().toUpperCase(Locale.ROOT);
        key = NAME_TO_IATA.getOrDefault(key, key);
        return Optional.ofNullable(properties.getCityNodes().get(key));
    }

    /** Hub coordinates for the city, if configured. */
    public Optional<Coord> hub(String cityCode) {
        return node(cityCode).map(n -> new Coord(n.getHubLat(), n.getHubLon()));
    }

    /** Airport coordinates for the city, if configured. */
    public Optional<Coord> airport(String cityCode) {
        return node(cityCode).map(n -> new Coord(n.getAirportLat(), n.getAirportLon()));
    }

    /** A plain lat/lon pair. */
    public record Coord(double lat, double lon) {}
}
