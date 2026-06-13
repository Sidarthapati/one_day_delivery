package com.oneday.routing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M6 routing configuration (design §17.3 fleet/window/dwell knobs + the calibratable constants
 * Q4/Q5/Q9). Per-city fleet rows live in {@code city_fleet_config}; these are engine-wide defaults.
 */
@Component
@ConfigurationProperties(prefix = "routing")
@Data
public class RoutingProperties {

    private Osrm osrm = new Osrm();
    private Solver solver = new Solver();
    private Cycle cycle = new Cycle();
    private Shuttle shuttle = new Shuttle();
    private Window window = new Window();

    /** Dwell window per stop — the van never waits past it (C7, M6-D-019, Q9). */
    private int dwellMinutes = 5;

    /** DA→meeting-vertex reachability bound; protects the DA's 70% utilisation (C5, NFR-4, Q4). */
    private int maxDaToVertexMinutes = 12;

    /** Lateness past plan that escalates to VAN_RUNNING_LATE (§14.4). */
    private int lateThresholdMinutes = 10;

    /** Min spacing between a DA's meeting times — must be ≥ M5's CRON_FREEZE_MINUTES (C6, §10). */
    private int cronFreezeMinutes = 30;

    /** Flat per-km van cost until M2's CostFloorPort lands (M6-D-010). INR. */
    private double costPerKm = 15.0;

    /**
     * Multiplier applied to OSRM free-flow travel times to approximate real urban congestion
     * (Q5 calibration). 1.0 = raw OSRM (optimistic). Demo uses ~1.6 so loop times — and therefore
     * the fleet/loop counts — are realistic rather than highway-speed.
     */
    private double congestionFactor = 1.0;

    /**
     * Minutes a van spends at the hub on each loop return: unload collected parcels + load the next
     * loop's deliveries. Added as the hub node's service time so it counts against the cycle and the
     * loops-per-day sizing. 0 = not modelled (old behaviour).
     */
    private int hubTurnaroundMinutes = 0;

    // Maps cityCode (e.g. "delhi") → fixed cityId UUID, shared with M3's grid.cities.
    private Map<String, UUID> cities = new HashMap<>();

    @Data
    public static class Osrm {
        // M6 builds its own travel matrix over {hub} ∪ vertices (∪ airport) — M6-D-009.
        private String baseUrl = "http://localhost:5000";
    }

    @Data
    public static class Solver {
        private int timeLimitSeconds = 45;
    }

    @Data
    public static class Cycle {
        // Target 2h, hard max 3h (C3, M6-D-003, Q5).
        private int minMinutes = 120;
        private int maxMinutes = 180;
    }

    @Data
    public static class Shuttle {
        private int cadenceMinutes = 30;
    }

    @Data
    public static class Window {
        // Operating window, shared with M3's shift (07:00–20:00).
        private int startHour = 7;
        private int endHour = 20;
    }
}
