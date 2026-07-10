package com.oneday.routing.demo;

import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.LogisticsNodeKind;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.dto.TelemetryType;
import com.oneday.routing.dto.VanTelemetryRequest;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.CustodyService;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.VanTrackingService;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.routing.service.port.ScanLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Demo-only reusable van replay (ported from the M6 execution demo's {@code DemoExecutionService}
 * drive-half). Given an APPROVED route plan whose manifests M6 has already bound parcels onto, it
 * animates every (van, loop): GPS pings → ARRIVED telemetry → per-stop custody scans → return + unload.
 *
 * <p>Split out of the app-level journey driver so routing internals (repositories, {@link GridDataAdapter},
 * {@link VanTrackingService}, {@link CustodyService}) stay inside the routing module; the app orchestrator
 * calls {@link #waitForBinds} then {@link #driveLoops}. {@code @Profile("!prod")} — never in production.</p>
 */
@Service
@Profile("!prod")
public class DemoVanDriver {

    private static final Logger log = LoggerFactory.getLogger(DemoVanDriver.class);

    /** GPS pings interpolated along each leg (smoother marker motion vs. more ticks). */
    private static final int GPS_PER_LEG = 5;

    /** Sink for the app's live feed panel — one line per van action. */
    public interface FeedSink {
        void add(String kind, String message);
    }

    /** Outcome of driving a city's loops. */
    public record DriveResult(int delivered, int collected) {}

    private final GridDataAdapter gridDataAdapter;
    private final CityLogisticsNodeRepository nodeRepository;
    private final RoutePlanRepository planRepository;
    private final RoutePlanStopRepository stopRepository;
    private final VanManifestRepository manifestRepository;
    private final VanManifestItemRepository itemRepository;
    private final VanTrackingService trackingService;
    private final CustodyService custodyService;
    private final RoutingProperties properties;
    private final Clock clock;

    DemoVanDriver(GridDataAdapter gridDataAdapter, CityLogisticsNodeRepository nodeRepository,
                  RoutePlanRepository planRepository, RoutePlanStopRepository stopRepository,
                  VanManifestRepository manifestRepository, VanManifestItemRepository itemRepository,
                  VanTrackingService trackingService, CustodyService custodyService,
                  RoutingProperties properties, Clock clock) {
        this.gridDataAdapter = gridDataAdapter;
        this.nodeRepository = nodeRepository;
        this.planRepository = planRepository;
        this.stopRepository = stopRepository;
        this.manifestRepository = manifestRepository;
        this.itemRepository = itemRepository;
        this.trackingService = trackingService;
        this.custodyService = custodyService;
        this.properties = properties;
        this.clock = clock;
    }

    /** The APPROVED plan for this city/date, if one exists (binding + driving both key off it). */
    public Optional<RoutePlan> approvedPlan(UUID cityId, LocalDate date) {
        return planRepository.findByCityIdAndValidForDateAndStatus(cityId, date, RoutePlanStatus.APPROVED)
                .stream().findFirst();
    }

    /**
     * Poll the plan's bound-item count until it settles (consumers caught up over the broker) or all
     * {@code expected} are bound. Binding is async and lags publishing, so the real exit is "count
     * stopped growing for ~1s"; the volume-scaled cap is just a safety net.
     */
    public int waitForBinds(UUID planId, int expected, BooleanSupplier cancelled) {
        int stable = 0, last = -1;
        int maxPolls = 100 + expected;
        for (int i = 0; i < maxPolls && !cancelled.getAsBoolean(); i++) {
            int bound = countItems(planId);
            if (bound == last) {
                if (++stable >= 5 && bound > 0) return bound;   // ~1s with no new binds → settled
            } else {
                stable = 0;
            }
            last = bound;
            if (expected > 0 && bound >= expected) return bound;
            settle(200);
        }
        return Math.max(0, last);
    }

    /**
     * Drive every bound (van, loop) manifest of the city's APPROVED plan, stepping all vans together
     * one action per tick so they move simultaneously on the map. Returns the delivered/collected tally.
     */
    public DriveResult driveLoops(UUID cityId, LocalDate date, int tickMs, FeedSink feed, BooleanSupplier cancelled) {
        RoutePlan plan = approvedPlan(cityId, date).orElse(null);
        if (plan == null) {
            feed.add("WARN", "No APPROVED route plan for city " + shortId(cityId) + " — cannot drive vans.");
            return new DriveResult(0, 0);
        }

        Map<UUID, double[]> vertexCoords = gridDataAdapter.vertexCoords(cityId);
        double[] hub = nodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB)
                .map(n -> new double[]{n.getLat(), n.getLon()})
                .orElseGet(() -> firstVertexFallback(vertexCoords));

        List<VanManifest> manifests = manifestRepository.findByRoutePlanId(plan.getId()).stream()
                .filter(m -> !itemRepository.findByManifestId(m.getId()).isEmpty())
                .sorted(Comparator.comparingInt(VanManifest::getLoopIndex))
                .toList();
        if (manifests.isEmpty()) {
            feed.add("WARN", "No bound manifests to drive for city " + shortId(cityId) + ".");
            return new DriveResult(0, 0);
        }

        Random rng = new Random();
        int[] tally = {0, 0}; // [delivered, collected]
        List<Deque<Runnable>> programs = new ArrayList<>();
        for (VanManifest m : manifests) {
            programs.add(buildProgram(plan, m, cityId, date, vertexCoords, hub, rng.nextInt(100), tally, feed));
        }
        feed.add("INFO", "Driving %d van loop(s) in %s…".formatted(programs.size(), shortId(cityId)));

        while (!cancelled.getAsBoolean()) {
            boolean any = false;
            for (Deque<Runnable> p : programs) {
                if (!p.isEmpty()) {
                    any = true;
                    try {
                        p.poll().run();
                    } catch (RuntimeException e) {
                        log.warn("Demo van step failed: {}", e.getMessage());
                    }
                }
            }
            if (!any) break;
            settle(tickMs);
        }
        return new DriveResult(tally[0], tally[1]);
    }

    // ── the per-loop action program (hub load → stops → return + unload) ─────────────────────────

    private Deque<Runnable> buildProgram(RoutePlan plan, VanManifest manifest, UUID cityId, LocalDate date,
                                         Map<UUID, double[]> vertexCoords, double[] hub, int latePct,
                                         int[] tally, FeedSink feed) {
        UUID vanId = manifest.getVanId();
        int loop = manifest.getLoopIndex();
        List<VanManifestItem> items = itemRepository.findByManifestId(manifest.getId());
        List<RoutePlanStop> stops = stopRepository
                .findByRoutePlanIdAndVanIdAndLoopIndexOrderByStopSeq(plan.getId(), vanId, loop).stream()
                .filter(s -> s.getNodeKind() == StopNodeKind.MEETING_VERTEX && s.getHexVertexId() != null)
                .toList();

        int lateOffset = latenessOffset(latePct);
        Deque<Runnable> steps = new ArrayDeque<>();

        // 1. Hub load — DELIVER items board the van (PLANNED → LOADED; seals manifest LOADED).
        List<VanManifestItem> deliverItems = items.stream()
                .filter(it -> it.getDirection() == HandoffDirection.DELIVER).toList();
        if (!deliverItems.isEmpty()) {
            steps.add(() -> {
                int n = 0;
                for (VanManifestItem it : deliverItems) {
                    if (custody(it, ScanLedgerPort.VanScanType.VAN_LOAD, vanId).accepted()) n++;
                }
                feed.add("LOAD", "Van %s loop %d ▸ loaded %d parcel(s) at hub (LOADED)".formatted(shortId(vanId), loop, n));
            });
        }

        // 2. Drive stop to stop: GPS pings → ARRIVED → custody scans for that stop.
        double[] prev = hub;
        for (RoutePlanStop stop : stops) {
            double[] target = vertexCoords.getOrDefault(stop.getHexVertexId(), prev);
            addLeg(steps, vanId, cityId, loop, prev, target);

            int stopSeq = stop.getStopSeq();
            UUID vertexId = stop.getHexVertexId();
            LocalTime planned = stop.getPlannedArrival();
            double[] arr = target;
            steps.add(() -> {
                Instant arrivalTs = arrivalInstant(date, planned, stopSeq, lateOffset);
                trackingService.handle(vanId, new VanTelemetryRequest(TelemetryType.ARRIVED_AT_STOP,
                        arr[0], arr[1], arrivalTs, cityId, loop, stopSeq, vertexId, null, null, null));
                boolean late = lateOffset >= properties.getLateThresholdMinutes();
                feed.add(late ? "LATE" : "ARRIVE", "Van %s loop %d ▸ arrived stop %d (%s)".formatted(
                        shortId(vanId), loop, stopSeq, late ? "⚠ +%dm".formatted(lateOffset)
                                : (lateOffset > 0 ? "+%dm".formatted(lateOffset) : "on time")));
            });

            List<VanManifestItem> atStop = items.stream()
                    .filter(it -> it.getStopSeq() != null && it.getStopSeq() == stopSeq).toList();
            steps.add(() -> {
                int d = 0, c = 0;
                for (VanManifestItem it : atStop) {
                    if (it.getDirection() == HandoffDirection.DELIVER) {
                        if (scan(vanId, cityId, loop, stopSeq, it, TelemetryType.DELIVER)) d++;
                    } else {
                        if (scan(vanId, cityId, loop, stopSeq, it, TelemetryType.COLLECT)) c++;
                    }
                }
                tally[0] += d;
                tally[1] += c;
                if (d + c > 0) {
                    feed.add("SCAN", "Van %s loop %d stop %d ▸ delivered %d, collected %d"
                            .formatted(shortId(vanId), loop, stopSeq, d, c));
                }
            });
            prev = target;
        }

        // 3. Return to hub: GPS back, mark RETURNED, unload COLLECTED parcels (ONBOARD → RECONCILED).
        addLeg(steps, vanId, cityId, loop, prev, hub);
        List<VanManifestItem> collectItems = items.stream()
                .filter(it -> it.getDirection() == HandoffDirection.COLLECT).toList();
        steps.add(() -> {
            markReturned(vanId, loop, date);
            int n = 0;
            for (VanManifestItem it : collectItems) {
                if (custody(it, ScanLedgerPort.VanScanType.VAN_UNLOAD, vanId).accepted()) n++;
            }
            feed.add("RETURN", n > 0
                    ? "Van %s loop %d ▸ returned to hub, %d collected parcel(s) unloaded → RECONCILED"
                        .formatted(shortId(vanId), loop, n)
                    : "Van %s loop %d ▸ returned to hub".formatted(shortId(vanId), loop));
        });
        return steps;
    }

    private void addLeg(Deque<Runnable> steps, UUID vanId, UUID cityId, int loop, double[] from, double[] to) {
        for (int i = 1; i <= GPS_PER_LEG; i++) {
            double f = (double) i / GPS_PER_LEG;
            double lat = from[0] + (to[0] - from[0]) * f;
            double lon = from[1] + (to[1] - from[1]) * f;
            steps.add(() -> trackingService.handle(vanId, new VanTelemetryRequest(TelemetryType.GPS,
                    lat, lon, null, cityId, loop, null, null, null, null, null)));
        }
    }

    private boolean scan(UUID vanId, UUID cityId, int loop, int stopSeq, VanManifestItem it, TelemetryType type) {
        var ack = trackingService.handle(vanId, new VanTelemetryRequest(type, null, null, clock.instant(),
                cityId, loop, stopSeq, it.getMeetingVertexId(), it.getParcelId(), it.getCounterpartyDaId(), null));
        return ack != null;
    }

    private com.oneday.routing.service.model.CustodyResult custody(VanManifestItem it,
                                                                   ScanLedgerPort.VanScanType type, UUID vanId) {
        return custodyService.record(new VanCustodyCommand(
                it.getParcelId(), type, vanId, null, it.getCounterpartyDaId(), clock.instant()));
    }

    private void markReturned(UUID vanId, int loop, LocalDate date) {
        manifestRepository.findByVanIdAndLoopIndexAndValidDate(vanId, loop, date).ifPresent(m -> {
            if (m.getStatus() == ManifestStatus.IN_PROGRESS || m.getStatus() == ManifestStatus.LOADED) {
                m.setStatus(ManifestStatus.RETURNED);
                m.setReturnedAt(clock.instant());
                manifestRepository.save(m);
            }
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private int countItems(UUID planId) {
        return manifestRepository.findByRoutePlanId(planId).stream()
                .mapToInt(m -> itemRepository.findByManifestId(m.getId()).size()).sum();
    }

    private Instant arrivalInstant(LocalDate date, LocalTime planned, int stopSeq, int lateOffset) {
        LocalTime base = planned != null
                ? planned
                : LocalTime.of(properties.getWindow().getStartHour(), 0).plusMinutes(stopSeq * 15L);
        return date.atTime(base).atZone(clock.getZone()).toInstant().plus(Duration.ofMinutes(lateOffset));
    }

    // Mostly on-time, a minority late, ~15% past the late threshold (so VAN_RUNNING_LATE shows up).
    private int latenessOffset(int pct) {
        Random rng = new Random();
        if (pct < 60) return rng.nextInt(6);
        if (pct < 85) return 6 + rng.nextInt(4);
        return properties.getLateThresholdMinutes() + 2 + rng.nextInt(10);
    }

    private double[] firstVertexFallback(Map<UUID, double[]> vertexCoords) {
        return vertexCoords.values().stream().findFirst().orElse(new double[]{28.6139, 77.2090});
    }

    private static String shortId(UUID id) {
        return id == null ? "—" : id.toString().substring(0, 8);
    }

    private void settle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
