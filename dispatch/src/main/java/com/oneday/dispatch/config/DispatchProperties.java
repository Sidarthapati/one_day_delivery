package com.oneday.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable threshold in M5 lives here — there are NO hardcoded literals anywhere else in the
 * module. Bound from the {@code dispatch.*} tree:
 *
 * <pre>{@code
 * dispatch:
 *   cron:
 *     freeze-minutes: 30
 *     proximity-meters: 200
 *   da:
 *     absent-threshold-minutes: 15
 *   gps:
 *     heartbeat-interval-seconds: 30
 *     flush-interval-seconds: 120
 *   osrm:
 *     confirm-threshold-minutes: 20
 *   travel:
 *     road-factor: 1.4
 *     avg-speed-kmph: 25
 *     breaker-fallback-multiplier: 1.2   # extra margin when OSRM is down on a borderline check
 *   shift:
 *     load-offset-minutes: 15
 *     load-cron: "0 45 5,13 * * *"   # 15 min before the 06:00 / 14:00 IST shift starts
 *     zone: Asia/Kolkata
 *     cities: [delhi, mumbai, bengaluru, hyderabad, chennai]
 * }</pre>
 */
@ConfigurationProperties(prefix = "dispatch")
public class DispatchProperties {

    @NestedConfigurationProperty
    private Cron cron = new Cron();
    @NestedConfigurationProperty
    private Da da = new Da();
    @NestedConfigurationProperty
    private Gps gps = new Gps();
    @NestedConfigurationProperty
    private Osrm osrm = new Osrm();
    @NestedConfigurationProperty
    private Travel travel = new Travel();
    @NestedConfigurationProperty
    private Shift shift = new Shift();
    @NestedConfigurationProperty
    private Monitor monitor = new Monitor();
    @NestedConfigurationProperty
    private Events events = new Events();
    @NestedConfigurationProperty
    private Service service = new Service();
    @NestedConfigurationProperty
    private CrossTerritory crossTerritory = new CrossTerritory();
    @NestedConfigurationProperty
    private Deferred deferred = new Deferred();
    @NestedConfigurationProperty
    private Dlq dlq = new Dlq();

    public Cron getCron() { return cron; }
    public void setCron(Cron cron) { this.cron = cron; }
    public Da getDa() { return da; }
    public void setDa(Da da) { this.da = da; }
    public Gps getGps() { return gps; }
    public void setGps(Gps gps) { this.gps = gps; }
    public Osrm getOsrm() { return osrm; }
    public void setOsrm(Osrm osrm) { this.osrm = osrm; }
    public Travel getTravel() { return travel; }
    public void setTravel(Travel travel) { this.travel = travel; }
    public Shift getShift() { return shift; }
    public void setShift(Shift shift) { this.shift = shift; }
    public Monitor getMonitor() { return monitor; }
    public void setMonitor(Monitor monitor) { this.monitor = monitor; }
    public Events getEvents() { return events; }
    public void setEvents(Events events) { this.events = events; }
    public Service getService() { return service; }
    public void setService(Service service) { this.service = service; }
    public CrossTerritory getCrossTerritory() { return crossTerritory; }
    public void setCrossTerritory(CrossTerritory crossTerritory) { this.crossTerritory = crossTerritory; }
    public Deferred getDeferred() { return deferred; }
    public void setDeferred(Deferred deferred) { this.deferred = deferred; }
    public Dlq getDlq() { return dlq; }
    public void setDlq(Dlq dlq) { this.dlq = dlq; }

    /** Cron-meeting protection (the hard constraint). */
    public static class Cron {
        /** Within this many minutes of the scheduled meeting, a DA is frozen (CRON_LOCKED). */
        private int freezeMinutes = 30;
        /** GPS within this radius of the cron vertex flips CRON_LOCKED → AT_CRON. */
        private int proximityMeters = 200;

        public int getFreezeMinutes() { return freezeMinutes; }
        public void setFreezeMinutes(int freezeMinutes) { this.freezeMinutes = freezeMinutes; }
        public int getProximityMeters() { return proximityMeters; }
        public void setProximityMeters(int proximityMeters) { this.proximityMeters = proximityMeters; }
    }

    public static class Da {
        /** GPS silent for longer than this → ABSENT. */
        private int absentThresholdMinutes = 15;

        public int getAbsentThresholdMinutes() { return absentThresholdMinutes; }
        public void setAbsentThresholdMinutes(int absentThresholdMinutes) {
            this.absentThresholdMinutes = absentThresholdMinutes;
        }
    }

    public static class Gps {
        /** Expected ping cadence from the DA app. */
        private int heartbeatIntervalSeconds = 30;
        /** How often dirty in-memory status rows are batch-flushed to {@code da_status}. */
        private int flushIntervalSeconds = 120;

        public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
        public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        }
        public int getFlushIntervalSeconds() { return flushIntervalSeconds; }
        public void setFlushIntervalSeconds(int flushIntervalSeconds) {
            this.flushIntervalSeconds = flushIntervalSeconds;
        }
    }

    public static class Osrm {
        /** Cron feasibility margin: confirm only if the parcel can reach the hub this far ahead. */
        private int confirmThresholdMinutes = 20;

        public int getConfirmThresholdMinutes() { return confirmThresholdMinutes; }
        public void setConfirmThresholdMinutes(int confirmThresholdMinutes) {
            this.confirmThresholdMinutes = confirmThresholdMinutes;
        }
    }

    public static class Travel {
        /** Multiplier on straight-line distance to approximate road distance (haversine fallback). */
        private double roadFactor = 1.4;
        /** Assumed average DA speed for the haversine ETA fallback. */
        private double avgSpeedKmph = 25;
        /**
         * Extra safety multiplier applied to {@code roadFactor} when OSRM is unavailable (circuit
         * breaker open) on a borderline cron-feasibility check, so the haversine estimate errs
         * conservative. Effective factor = {@code roadFactor × breakerFallbackMultiplier}.
         */
        private double breakerFallbackMultiplier = 1.2;

        public double getRoadFactor() { return roadFactor; }
        public void setRoadFactor(double roadFactor) { this.roadFactor = roadFactor; }
        public double getAvgSpeedKmph() { return avgSpeedKmph; }
        public void setAvgSpeedKmph(double avgSpeedKmph) { this.avgSpeedKmph = avgSpeedKmph; }
        public double getBreakerFallbackMultiplier() { return breakerFallbackMultiplier; }
        public void setBreakerFallbackMultiplier(double breakerFallbackMultiplier) {
            this.breakerFallbackMultiplier = breakerFallbackMultiplier;
        }
    }

    public static class Shift {
        /** Shift roster + queues are loaded this many minutes before shift start. */
        private int loadOffsetMinutes = 15;
        /** Cron trigger for {@code ShiftLoadJob}; default = 15 min before 06:00 and 14:00. */
        private String loadCron = "0 45 5,13 * * *";
        /** Cron trigger for {@code ShiftEndJob}; default = 5 min after 14:00 and 22:00. */
        private String endCron = "0 5 14,22 * * *";
        /** Time zone the shift crons are evaluated in. */
        private String zone = "Asia/Kolkata";
        /** City codes (as known to M3) whose rosters are loaded at shift start. */
        private List<String> cities = new ArrayList<>();

        public int getLoadOffsetMinutes() { return loadOffsetMinutes; }
        public void setLoadOffsetMinutes(int loadOffsetMinutes) { this.loadOffsetMinutes = loadOffsetMinutes; }
        public String getLoadCron() { return loadCron; }
        public void setLoadCron(String loadCron) { this.loadCron = loadCron; }
        public String getEndCron() { return endCron; }
        public void setEndCron(String endCron) { this.endCron = endCron; }
        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone; }
        public List<String> getCities() { return cities; }
        public void setCities(List<String> cities) { this.cities = cities; }
    }

    /** Cadence of the always-on monitor jobs (cron-lock + absent detection). */
    public static class Monitor {
        /** How often CronMonitorJob and AbsentDaDetectionJob run. Default: 5 minutes. */
        private int intervalSeconds = 300;

        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    }

    /** Per-stop service time used by cron feasibility until M3 per-tile service times are wired. */
    public static class Service {
        /** On-site service minutes per stop (design §9.3 default; M3 tile_demand_snapshot supersedes later). */
        private int defaultMinutes = 12;

        public int getDefaultMinutes() { return defaultMinutes; }
        public void setDefaultMinutes(int defaultMinutes) { this.defaultMinutes = defaultMinutes; }
    }

    /**
     * Cross-territory dispatch (design §10): when a tile is overloaded and a neighbouring DA is
     * sparse, an infeasible pickup may be handed to that adjacent DA. Disabled by default in v1 —
     * M3 exposes per-tile load scores but not tile adjacency yet, so {@code AdjacentDaProvider} has
     * no real source and the engine simply defers (the plan's sanctioned v1 fallback).
     */
    public static class CrossTerritory {
        private boolean enabled = false;
        /** Origin tile must be at least this loaded (adjustedLoadScore) to spill over. */
        private double overloadThreshold = 1.5;
        /** A candidate adjacent tile must be below this load to receive the spillover. */
        private double sparseThreshold = 0.8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getOverloadThreshold() { return overloadThreshold; }
        public void setOverloadThreshold(double overloadThreshold) { this.overloadThreshold = overloadThreshold; }
        public double getSparseThreshold() { return sparseThreshold; }
        public void setSparseThreshold(double sparseThreshold) { this.sparseThreshold = sparseThreshold; }
    }

    /** Deferred-dispatch retry policy (DeferredRetryJob). */
    public static class Deferred {
        /** Escalate to M11 after this many failed retries. */
        private int maxRetries = 3;
        /** A failed retry pushes the next attempt out by this many minutes. */
        private int retryIntervalMinutes = 5;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getRetryIntervalMinutes() { return retryIntervalMinutes; }
        public void setRetryIntervalMinutes(int retryIntervalMinutes) {
            this.retryIntervalMinutes = retryIntervalMinutes;
        }
    }

    /** Dead-letter re-drive bounds. */
    public static class Dlq {
        /** Max messages drained from a DLQ per replay call (safety bound). */
        private int replayBatchLimit = 500;

        public int getReplayBatchLimit() { return replayBatchLimit; }
        public void setReplayBatchLimit(int replayBatchLimit) { this.replayBatchLimit = replayBatchLimit; }
    }

    /** Outbound-event gating. */
    public static class Events {
        /**
         * When true, {@code DaEventProducer} publishes to {@code EventStreams.DA_EVENTS}. Default
         * TRUE: the M5↔M6 contract is settled — {@code DaLifecycleEvent} is the single rich type on
         * that exchange and both consumers (M4, M6) dispatch by {@code eventType}, so the {@code #}
         * binding is safe. Kept as a kill-switch (set false to suppress publishing; events are logged).
         */
        private boolean publishDaEvents = true;

        /**
         * When true, {@code TileQueueDepthPublisher} publishes to {@code EventStreams.TILE_QUEUE_DEPTH}.
         * Default TRUE: M5 is the confirmed sole producer of this feed — M3 needs dispatch reality
         * (parcels queued per tile from {@code dispatch_queue} + live DA territory), which only M5 has;
         * M4 must NOT also publish it.
         */
        private boolean publishTileQueueDepth = true;

        public boolean isPublishDaEvents() { return publishDaEvents; }
        public void setPublishDaEvents(boolean publishDaEvents) { this.publishDaEvents = publishDaEvents; }
        public boolean isPublishTileQueueDepth() { return publishTileQueueDepth; }
        public void setPublishTileQueueDepth(boolean publishTileQueueDepth) {
            this.publishTileQueueDepth = publishTileQueueDepth;
        }
    }
}
