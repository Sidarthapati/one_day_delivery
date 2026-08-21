package com.oneday.sla.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Current weather per operating city, for the ops control tower's weather-aware triage. Pulls
 * Open-Meteo (keyless, free) once for all five metros and caches it for an hour — weather moves
 * slowly and the sweeper reads {@link #adverseCities()} every 60s. Fully best-effort: a provider
 * hiccup yields an empty adverse set (no boost, no advisory) and never breaks scoring.
 *
 * <p>ponytail: JDK HttpClient + one cached multi-city call — no new dependency, no per-parcel fetch.
 * Open-Meteo's free tier is non-commercial; all weather I/O is behind this one class, so swapping to
 * a keyed provider later is a single-file change.</p>
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final long TTL_MILLIS = 60 * 60 * 1000L; // 1h

    /** The five pilot metros (IATA → lat/lon), matching the station console's city list. */
    private static final Map<String, double[]> CITIES = Map.of(
            "DEL", new double[]{28.61, 77.21},
            "BOM", new double[]{19.08, 72.88},
            "BLR", new double[]{12.97, 77.59},
            "HYD", new double[]{17.39, 78.49},
            "MAA", new double[]{13.08, 80.27});
    // Stable order so the multi-city response maps back correctly.
    private static final String[] ORDER = {"DEL", "BOM", "BLR", "HYD", "MAA"};

    /** One city's current conditions. {@code adverse} = weather that slows last-mile / delays flights. */
    public record CityWeather(String code, double tempC, int wmo, String condition, boolean adverse) {}

    private record Cache(long at, Map<String, CityWeather> byCity) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Cache> cache = new AtomicReference<>();

    /** Current weather for every operating city (cached ~1h). Empty on a provider failure. */
    public Map<String, CityWeather> current() {
        Cache c = cache.get();
        if (c != null && System.currentTimeMillis() - c.at() < TTL_MILLIS) {
            return c.byCity();
        }
        Map<String, CityWeather> fresh = fetch();
        // Only overwrite the cache on success; keep serving the last good reading through an outage.
        if (!fresh.isEmpty()) {
            cache.set(new Cache(System.currentTimeMillis(), fresh));
            return fresh;
        }
        return c != null ? c.byCity() : Map.of();
    }

    /** IATA codes whose weather is currently adverse for logistics (fog / heavy rain / storm / snow). */
    public Set<String> adverseCities() {
        return current().values().stream()
                .filter(CityWeather::adverse)
                .map(CityWeather::code)
                .collect(Collectors.toSet());
    }

    /**
     * The city whose weather actually bears on this parcel right now: origin while it's still being
     * picked up, destination once it's moving toward delivery. (Weather during the air leg matters at
     * the destination it's landing into.)
     */
    public static String relevantCity(com.oneday.common.domain.enums.SlaLegType currentLeg,
                                      String originCity, String destCity) {
        if (currentLeg == com.oneday.common.domain.enums.SlaLegType.FIRST_MILE
                || currentLeg == com.oneday.common.domain.enums.SlaLegType.ORIGIN_HUB) {
            return originCity;
        }
        return destCity;
    }

    /**
     * A ground leg where weather actually slows the parcel (pickup or delivery on the road, hub yards).
     * Mid-air is excluded — rain doesn't speed or slow a plane in cruise, and the manager can't act on it.
     */
    public static boolean isWeatherExposedLeg(com.oneday.common.domain.enums.SlaLegType leg) {
        return leg != null && leg != com.oneday.common.domain.enums.SlaLegType.AIR;
    }

    private Map<String, CityWeather> fetch() {
        try {
            String lat = String.join(",", java.util.Arrays.stream(ORDER).map(c -> String.valueOf(CITIES.get(c)[0])).toList());
            String lon = String.join(",", java.util.Arrays.stream(ORDER).map(c -> String.valueOf(CITIES.get(c)[1])).toList());
            URI uri = URI.create("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code&timezone=auto");
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("Open-Meteo returned {}", res.statusCode());
                return Map.of();
            }
            JsonNode body = mapper.readTree(res.body());
            // Multi-city responses come back as a JSON array, one entry per coord, in request order.
            Map<String, CityWeather> out = new LinkedHashMap<>();
            for (int i = 0; i < ORDER.length; i++) {
                JsonNode e = body.isArray() ? body.get(i) : body;
                if (e == null) continue;
                JsonNode cur = e.path("current");
                double temp = cur.path("temperature_2m").asDouble(Double.NaN);
                int wmo = cur.path("weather_code").asInt(-1);
                out.put(ORDER[i], new CityWeather(ORDER[i], temp, wmo, describe(wmo), isAdverse(wmo)));
            }
            return out;
        } catch (Exception ex) {
            log.warn("Weather fetch failed: {}", ex.toString());
            return Map.of();
        }
    }

    /**
     * WMO codes that slow ground delivery or threaten flights: fog (45,48) and any precipitation
     * (drizzle 51–57, rain 61–67, snow 71–77, showers 80–86, thunderstorm 95–99). Everything below 45
     * is clear/cloud. ponytail: threshold is a calibration knob — raise it if drizzle proves too noisy.
     */
    private static boolean isAdverse(int wmo) {
        return wmo >= 45;
    }

    private static String describe(int wmo) {
        if (wmo <= 0) return "Clear";
        if (wmo <= 3) return "Cloudy";
        if (wmo <= 48) return "Fog";
        if (wmo <= 57) return "Drizzle";
        if (wmo <= 67) return "Rain";
        if (wmo <= 77) return "Snow";
        if (wmo <= 82) return "Showers";
        if (wmo <= 86) return "Snow showers";
        return "Thunderstorm";
    }
}
