package com.oneday.routing.demo;

import com.oneday.common.kafka.EventStreams;
import com.oneday.common.port.DaPickupQueuePort;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityLogisticsNode;
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
import com.oneday.routing.events.payload.DaParcelPickedUpEvent;
import com.oneday.routing.events.payload.ParcelSortedForDeliveryEvent;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.HandoffReconciliationRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanLiveStatusRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.CustodyService;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.VanTrackingService;
import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.TerritoryHex;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.routing.service.port.ScanLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Demo-only "run the day" driver (no PR8 sim harness). It exercises the already-built M6 run-time path
 * end to end against an APPROVED plan, so a live audience can watch it on the map:
 *
 * <ol>
 *   <li><b>Feed over RabbitMQ</b> — publish synthetic {@code ParcelSortedForDelivery}/{@code DaParcelPickedUp}
 *       events to the real {@code oneday.hub.events}/{@code oneday.da.events} exchanges (CloudAMQP). The
 *       app's own {@code HubFeedConsumer}/{@code DaFeedConsumer} consume them and bind each parcel to a
 *       van/loop — proving the bus + binding engine, not a shortcut.</li>
 *   <li><b>Drive the vans</b> — replay the plan's stops as telemetry (GPS → ARRIVED) + custody scans
 *       (LOAD → DELIVER/COLLECT → UNLOAD) through {@link VanTrackingService}/{@link CustodyService}, so
 *       {@code van_live_status} animates and every manifest item walks its lifecycle.</li>
 * </ol>
 *
 * All synthetic data is drawn from the live grid ({@link GridDataAdapter}) so binds resolve. Single run
 * at a time; a background thread steps every van one action per tick for simultaneous motion.
 */
@Service
@Profile("!prod")
public class DemoExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DemoExecutionService.class);

    // How many GPS pings to interpolate along each leg (smoother marker motion vs. more ticks).
    private static final int GPS_PER_LEG = 5;

    private final RabbitTemplate rabbitTemplate;
    private final GridDataAdapter gridDataAdapter;
    private final CityLogisticsNodeRepository nodeRepository;
    private final RoutePlanRepository planRepository;
    private final RoutePlanStopRepository stopRepository;
    private final DaCronScheduleRepository cronRepository;
    private final VanManifestRepository manifestRepository;
    private final VanManifestItemRepository itemRepository;
    private final HandoffReconciliationRepository handoffRepository;
    private final VanLiveStatusRepository liveStatusRepository;
    private final VanTrackingService trackingService;
    private final CustodyService custodyService;
    private final RoutingProperties properties;
    private final Clock clock;
    // M5's live pickup queue. Optional: present only when the dispatch demo bean is on the context
    // (routing depends on common, not dispatch), so the bridge is absent in routing's own tests.
    private final ObjectProvider<DaPickupQueuePort> pickupQueue;

    private final DemoLog feed = new DemoLog();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-run-day");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancel = false;
    private volatile RunStatus status = RunStatus.idle();

    DemoExecutionService(RabbitTemplate rabbitTemplate, GridDataAdapter gridDataAdapter,
                         CityLogisticsNodeRepository nodeRepository, RoutePlanRepository planRepository,
                         RoutePlanStopRepository stopRepository, DaCronScheduleRepository cronRepository,
                         VanManifestRepository manifestRepository, VanManifestItemRepository itemRepository,
                         HandoffReconciliationRepository handoffRepository, VanLiveStatusRepository liveStatusRepository,
                         VanTrackingService trackingService, CustodyService custodyService,
                         RoutingProperties properties, Clock clock,
                         ObjectProvider<DaPickupQueuePort> pickupQueue) {
        this.rabbitTemplate = rabbitTemplate;
        this.gridDataAdapter = gridDataAdapter;
        this.nodeRepository = nodeRepository;
        this.planRepository = planRepository;
        this.stopRepository = stopRepository;
        this.cronRepository = cronRepository;
        this.manifestRepository = manifestRepository;
        this.itemRepository = itemRepository;
        this.handoffRepository = handoffRepository;
        this.liveStatusRepository = liveStatusRepository;
        this.trackingService = trackingService;
        this.custodyService = custodyService;
        this.properties = properties;
        this.clock = clock;
        this.pickupQueue = pickupQueue;
    }

    // ── public surface (controller) ──────────────────────────────────────────────────────────────

    public record RunStatus(String phase, UUID cityId, LocalDate date, int published, int bound,
                            int delivered, int collected, int vansActive, long lastSeq, String error) {
        static RunStatus idle() {
            return new RunStatus("IDLE", null, null, 0, 0, 0, 0, 0, 0, null);
        }
    }

    public synchronized RunStatus start(UUID cityId, LocalDate date, int deliveries, int collects, int speed) {
        if (running.get()) {
            throw new IllegalStateException("A demo run is already in progress — stop it first.");
        }
        LocalDate day = date != null ? date : LocalDate.now(clock);
        RoutePlan plan = planRepository
                .findByCityIdAndValidForDateAndStatus(cityId, day, RoutePlanStatus.APPROVED).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No APPROVED route plan for this city/date — generate & approve a plan first."));
        if (!day.equals(LocalDate.now(clock))) {
            // Telemetry resolves the manifest by today's date (VanTrackingService), so the run must be "today".
            throw new IllegalStateException("Demo run must be for today's date (telemetry resolves manifests by today).");
        }

        cancel = false;
        feed.clear();
        running.set(true);
        status = new RunStatus("STARTING", cityId, day, 0, 0, 0, 0, 0, feed.lastSeq(), null);
        int tickMs = Math.max(40, Math.min(1000, Math.round(200f * 60f / Math.max(1, speed))));
        runner.submit(() -> runDay(plan, cityId, day, deliveries, collects, tickMs));
        return status;
    }

    public void stop() {
        cancel = true;
        feed.add("INFO", "Stop requested — finishing current tick.");
    }

    public RunStatus status() {
        return status;
    }

    public List<DemoLog.Entry> events(long after) {
        return feed.since(after);
    }

    // ── the run ──────────────────────────────────────────────────────────────────────────────────

    private void runDay(RoutePlan plan, UUID cityId, LocalDate date, int deliveries, int collects, int tickMs) {
        try {
            feed.add("INFO", "Run the day — city %s, plan %s, speed tick %dms"
                    .formatted(shortId(cityId), shortId(plan.getId()), tickMs));

            // Manifests are keyed by (van, loop, date), shared across plan revisions of the same day — a
            // re-plan would otherwise bind onto a superseded plan's manifests. Clear the day so every run
            // binds fresh against the current plan. (Demo-only; real custody is append-only.)
            resetDay(cityId, date);

            int published = feedParcels(plan, cityId, date, deliveries, collects);
            setPhase("WAITING", published, 0, 0, 0, 0);

            // Binding is asynchronous (consumers off the broker); let the manifests settle before driving.
            int bound = waitForBinds(plan.getId(), published);
            feed.add("BIND", "Binding complete — %d parcels placed on van loops.".formatted(bound));

            setPhase("DRIVING", published, bound, 0, 0, 0);
            driveVans(plan, cityId, date, tickMs);

            setPhase(cancel ? "STOPPED" : "DONE", published, bound, status.delivered(), status.collected(), 0);
            feed.add("INFO", cancel ? "Run stopped." : "Day complete — every loop returned and reconciled.");
        } catch (RuntimeException e) {
            log.error("Demo run failed", e);
            status = new RunStatus("ERROR", cityId, date, status.published(), status.bound(),
                    status.delivered(), status.collected(), 0, feed.lastSeq(), e.getMessage());
            feed.add("ERROR", "Run failed: " + e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** Wipe the day's manifests/items/handoffs/live-status (across all plan revisions) so a run starts clean. */
    private void resetDay(UUID cityId, LocalDate date) {
        int mans = 0, items = 0;
        for (RoutePlan p : planRepository.findByCityIdAndValidForDate(cityId, date)) {
            for (VanManifest m : manifestRepository.findByRoutePlanId(p.getId())) {
                handoffRepository.deleteAll(handoffRepository.findByManifestId(m.getId()));
                List<VanManifestItem> its = itemRepository.findByManifestId(m.getId());
                itemRepository.deleteAll(its);
                manifestRepository.delete(m);
                items += its.size();
                mans++;
            }
        }
        liveStatusRepository.deleteAll(liveStatusRepository.findByCityId(cityId));
        if (mans > 0) {
            feed.add("INFO", "Cleared %d stale manifest(s) / %d item(s) from earlier plans or runs.".formatted(mans, items));
        }
    }

    /** Publish synthetic parcels to the real exchanges; the app's consumers bind them. Returns count published. */
    private int feedParcels(RoutePlan plan, UUID cityId, LocalDate date, int deliveries, int collects) {
        setPhase("FEEDING", 0, 0, 0, 0, 0);

        // Only DAs whose meeting vertex is actually VISITED (a stop in the plan) can be bound — drop-and-flag
        // may defer some cron'd vertices, so filtering by stop vertices (not just any cron row) keeps every
        // published parcel bindable. hexByDa lets a delivery target a real destination hex of that DA.
        Set<UUID> stopVertices = stopRepository.findByRoutePlanId(plan.getId()).stream()
                .filter(s -> s.getNodeKind() == StopNodeKind.MEETING_VERTEX && s.getHexVertexId() != null)
                .map(RoutePlanStop::getHexVertexId).collect(Collectors.toSet());
        List<UUID> planDas = cronRepository.findByRoutePlanId(plan.getId()).stream()
                .filter(c -> stopVertices.contains(c.getHexVertexId()))
                .map(c -> c.getDaId()).distinct().toList();
        Map<UUID, List<UUID>> hexByDa = gridDataAdapter.getDaTerritories(cityId, date).stream()
                .filter(t -> planDas.contains(t.daId()))
                .collect(Collectors.toMap(DaTerritory::daId,
                        t -> t.hexes().stream().map(TerritoryHex::hexId).toList(), (a, b) -> a));
        List<UUID> bindableDas = new ArrayList<>(hexByDa.keySet());
        if (bindableDas.isEmpty()) {
            throw new IllegalStateException("Plan has no DA meeting vertices to bind to — re-check the grid/plan.");
        }

        Random rng = new Random();
        int published = 0;
        Instant now = clock.instant();
        DaPickupQueuePort queue = pickupQueue.getIfAvailable();

        // Deliveries: prefer M5's real DELIVERY queue (one ParcelSortedForDelivery per task, to its dest
        // hex) so the map's drops equal what M5 dispatched. Fall back to synthetic ▼N when M5 has none.
        List<DaPickupQueuePort.QueuedPickup> m5Deliveries = queue == null ? List.of()
                : queue.queuedDeliveries(cityId, date).stream()
                        .filter(p -> bindableDas.contains(p.daId())).toList();
        if (!m5Deliveries.isEmpty()) {
            for (DaPickupQueuePort.QueuedPickup p : m5Deliveries) {
                rabbitTemplate.convertAndSend(EventStreams.HUB_EVENTS, "ParcelSortedForDelivery",
                        new ParcelSortedForDeliveryEvent(p.shipmentId(), cityId, p.tileId(), date, now, now.plus(Duration.ofHours(6))));
                feed.add("FEED", "HUB_EVENTS ▸ %s sorted for delivery → DA %s (M5 queue)"
                        .formatted(shortId(p.shipmentId()), shortId(p.daId())));
                published++;
                settle(25);
            }
            feed.add("INFO", "Sourced %d delivery(ies) from M5's live queue.".formatted(m5Deliveries.size()));
        } else {
            for (int i = 0; i < deliveries; i++) {
                UUID da = bindableDas.get(rng.nextInt(bindableDas.size()));
                List<UUID> hexes = hexByDa.get(da);
                if (hexes.isEmpty()) continue;
                UUID hex = hexes.get(rng.nextInt(hexes.size()));
                UUID parcel = UUID.randomUUID();
                rabbitTemplate.convertAndSend(EventStreams.HUB_EVENTS, "ParcelSortedForDelivery",
                        new ParcelSortedForDeliveryEvent(parcel, cityId, hex, date, now, now.plus(Duration.ofHours(6))));
                feed.add("FEED", "HUB_EVENTS ▸ %s sorted for delivery → DA %s (synthetic)".formatted(shortId(parcel), shortId(da)));
                published++;
                settle(25);
            }
        }
        // Collects: prefer M5's real pickup queue so "collected N" at each cron meeting equals exactly
        // what M5 dispatched to that DA (each queued pickup → a DaParcelPickedUp the van comes to
        // collect). Only DAs bindable in this plan can be collected, so filter to those. Fall back to
        // synthetic parcels when M5 hasn't run a shift for this city/day (queue empty or bridge absent).
        List<DaPickupQueuePort.QueuedPickup> m5Pickups = queue == null ? List.of()
                : queue.queuedPickups(cityId, date).stream()
                        .filter(p -> bindableDas.contains(p.daId())).toList();
        if (!m5Pickups.isEmpty()) {
            for (DaPickupQueuePort.QueuedPickup p : m5Pickups) {
                rabbitTemplate.convertAndSend(EventStreams.DA_EVENTS, "DaParcelPickedUp",
                        new DaParcelPickedUpEvent(p.shipmentId(), cityId, p.daId(), date, now));
                feed.add("FEED", "DA_EVENTS ▸ %s picked up by DA %s (M5 queue)"
                        .formatted(shortId(p.shipmentId()), shortId(p.daId())));
                published++;
                settle(25);
            }
            feed.add("INFO", "Sourced %d collect(s) from M5's live pickup queue.".formatted(m5Pickups.size()));
        } else {
            for (int i = 0; i < collects; i++) {
                UUID da = bindableDas.get(rng.nextInt(bindableDas.size()));
                UUID parcel = UUID.randomUUID();
                rabbitTemplate.convertAndSend(EventStreams.DA_EVENTS, "DaParcelPickedUp",
                        new DaParcelPickedUpEvent(parcel, cityId, da, date, now));
                feed.add("FEED", "DA_EVENTS ▸ %s picked up by DA %s (synthetic)".formatted(shortId(parcel), shortId(da)));
                published++;
                settle(25);
            }
        }
        feed.add("INFO", "Published %d parcels to RabbitMQ (CloudAMQP).".formatted(published));
        return published;
    }

    /**
     * Poll the plan's manifests until the bound item count settles (consumers caught up) or all expected
     * are bound. Binding is async over the broker and lags publishing — for large volumes it can take far
     * longer than the old fixed 8s window, so the real exit is "count stopped growing for ~1s"; the cap
     * (scaled to volume) is just a safety net. Driving snapshots manifests, so we must not cut off early.
     */
    private int waitForBinds(UUID planId, int expected) {
        int stable = 0, last = -1;
        int maxPolls = 100 + expected;                  // generous ceiling; stabilization exits well before
        for (int i = 0; i < maxPolls && !cancel; i++) {
            int bound = countItems(planId);
            if (bound == last) {
                if (++stable >= 5 && bound > 0) return bound;   // ~1s with no new binds → settled
            } else {
                stable = 0;
                if (i % 10 == 0) setPhase("WAITING", status.published(), bound, status.delivered(), status.collected(), 0);
            }
            last = bound;
            if (bound >= expected) return bound;
            settle(200);
        }
        return Math.max(0, last);
    }

    private int countItems(UUID planId) {
        return manifestRepository.findByRoutePlanId(planId).stream()
                .mapToInt(m -> itemRepository.findByManifestId(m.getId()).size()).sum();
    }

    /** Build one action-program per (van, loop) manifest and step them all together, one action per tick. */
    private void driveVans(RoutePlan plan, UUID cityId, LocalDate date, int tickMs) {
        Map<UUID, double[]> vertexCoords = gridDataAdapter.vertexCoords(cityId);
        double[] hub = nodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB)
                .map(n -> new double[]{n.getLat(), n.getLon()})
                .orElseGet(() -> firstVertexFallback(vertexCoords));

        List<VanManifest> manifests = manifestRepository.findByRoutePlanId(plan.getId()).stream()
                .filter(m -> !itemRepository.findByManifestId(m.getId()).isEmpty())
                .sorted(Comparator.comparingInt(VanManifest::getLoopIndex))
                .toList();

        Random rng = new Random();
        List<Deque<Runnable>> programs = new ArrayList<>();
        for (VanManifest m : manifests) {
            programs.add(buildProgram(plan, m, cityId, date, vertexCoords, hub, rng.nextInt(100)));
        }
        feed.add("INFO", "Driving %d van loops…".formatted(programs.size()));

        while (!cancel) {
            boolean any = false;
            int active = 0;
            for (Deque<Runnable> p : programs) {
                if (!p.isEmpty()) {
                    active++;
                    any = true;
                    try {
                        p.poll().run();
                    } catch (RuntimeException e) {
                        log.warn("Demo step failed: {}", e.getMessage());
                    }
                }
            }
            status = new RunStatus("DRIVING", status.cityId(), status.date(), status.published(),
                    status.bound(), status.delivered(), status.collected(), active, feed.lastSeq(), null);
            if (!any) break;
            settle(tickMs);
        }
    }

    private Deque<Runnable> buildProgram(RoutePlan plan, VanManifest manifest, UUID cityId, LocalDate date,
                                         Map<UUID, double[]> vertexCoords, double[] hub, int latePct) {
        UUID vanId = manifest.getVanId();
        int loop = manifest.getLoopIndex();
        List<VanManifestItem> items = itemRepository.findByManifestId(manifest.getId());
        List<RoutePlanStop> stops = stopRepository
                .findByRoutePlanIdAndVanIdAndLoopIndexOrderByStopSeq(plan.getId(), vanId, loop).stream()
                .filter(s -> s.getNodeKind() == StopNodeKind.MEETING_VERTEX && s.getHexVertexId() != null)
                .toList();

        int lateOffset = latenessOffset(latePct);
        Deque<Runnable> steps = new ArrayDeque<>();

        // 1. Hub load — deliver items board the van (PLANNED → LOADED; seals manifest LOADED).
        List<VanManifestItem> deliverItems = items.stream()
                .filter(it -> it.getDirection() == HandoffDirection.DELIVER).toList();
        steps.add(() -> {
            int n = 0;
            for (VanManifestItem it : deliverItems) {
                if (custody(it, ScanLedgerPort.VanScanType.VAN_LOAD, vanId).accepted()) n++;
            }
            feed.add("LOAD", "Van %s loop %d ▸ loaded %d parcels at hub (LOADED)".formatted(shortId(vanId), loop, n));
        });

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
                String late = lateOffset >= properties.getLateThresholdMinutes()
                        ? "⚠ RUNNING LATE +%dm".formatted(lateOffset)
                        : (lateOffset > 0 ? "+%dm".formatted(lateOffset) : "on time");
                feed.add(lateOffset >= properties.getLateThresholdMinutes() ? "LATE" : "ARRIVE",
                        "Van %s loop %d ▸ arrived stop %d (%s)".formatted(shortId(vanId), loop, stopSeq, late));
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
                bumpCounts(d, c);
                if (d + c > 0) {
                    feed.add("SCAN", "Van %s loop %d stop %d ▸ delivered %d, collected %d"
                            .formatted(shortId(vanId), loop, stopSeq, d, c));
                }
            });
            prev = target;
        }

        // 3. Return to hub: GPS back, mark RETURNED, unload collected parcels (ONBOARD → RECONCILED).
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
                    ? "Van %s loop %d ▸ returned to hub, %d collected parcels unloaded → RECONCILED"
                        .formatted(shortId(vanId), loop, n)
                    : "Van %s loop %d ▸ returned to hub (delivery-only loop, all handed off)"
                        .formatted(shortId(vanId), loop));
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

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private Instant arrivalInstant(LocalDate date, LocalTime planned, int stopSeq, int lateOffset) {
        LocalTime base = planned != null
                ? planned
                : LocalTime.of(properties.getWindow().getStartHour(), 0).plusMinutes(stopSeq * 15L);
        return date.atTime(base).atZone(clock.getZone()).toInstant().plus(Duration.ofMinutes(lateOffset));
    }

    // Mostly on-time, a minority late, ~15% past the late threshold (so VAN_RUNNING_LATE shows up).
    private int latenessOffset(int pct) {
        if (pct < 60) return new Random().nextInt(6);
        if (pct < 85) return 6 + new Random().nextInt(4);
        return properties.getLateThresholdMinutes() + 2 + new Random().nextInt(10);
    }

    private synchronized void bumpCounts(int d, int c) {
        status = new RunStatus(status.phase(), status.cityId(), status.date(), status.published(),
                status.bound(), status.delivered() + d, status.collected() + c, status.vansActive(),
                feed.lastSeq(), null);
    }

    private synchronized void setPhase(String phase, int published, int bound, int delivered, int collected,
                                       int vansActive) {
        status = new RunStatus(phase, status.cityId(), status.date(), published, bound,
                Math.max(delivered, status.delivered()), Math.max(collected, status.collected()),
                vansActive, feed.lastSeq(), null);
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
