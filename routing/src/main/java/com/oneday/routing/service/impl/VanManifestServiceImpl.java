package com.oneday.routing.service.impl;

import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.VanManifestService;
import com.oneday.routing.service.model.BindOutcome;
import com.oneday.routing.service.model.BindingResult;
import com.oneday.routing.service.model.LoopSlot;
import com.oneday.routing.service.model.ReconcileSummary;
import com.oneday.routing.service.port.DaAccumulationPort;
import com.oneday.routing.service.port.FlightCutoffPort;
import com.oneday.routing.service.port.HubSortPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
class VanManifestServiceImpl implements VanManifestService {

    private static final Logger log = LoggerFactory.getLogger(VanManifestServiceImpl.class);

    private final HubSortPort hubSortPort;
    private final DaAccumulationPort daAccumulationPort;
    private final FlightCutoffPort flightCutoffPort;
    private final RoutePlanRepository planRepository;
    private final RoutePlanStopRepository stopRepository;
    private final DaCronScheduleRepository cronRepository;
    private final CityFleetConfigRepository fleetConfigRepository;
    private final VanManifestRepository manifestRepository;
    private final VanManifestItemRepository itemRepository;
    private final GridDataAdapter gridDataAdapter;
    private final CronEventProducer cronEventProducer;
    private final RoutingProperties properties;

    // Per-(city,date) immutable plan data, safe to cache for the day (nightly-stability invariant).
    private final Map<String, PlanCtx> contextCache = new ConcurrentHashMap<>();

    VanManifestServiceImpl(HubSortPort hubSortPort, DaAccumulationPort daAccumulationPort,
                           FlightCutoffPort flightCutoffPort, RoutePlanRepository planRepository,
                           RoutePlanStopRepository stopRepository, DaCronScheduleRepository cronRepository,
                           CityFleetConfigRepository fleetConfigRepository, VanManifestRepository manifestRepository,
                           VanManifestItemRepository itemRepository, GridDataAdapter gridDataAdapter,
                           CronEventProducer cronEventProducer, RoutingProperties properties) {
        this.hubSortPort = hubSortPort;
        this.daAccumulationPort = daAccumulationPort;
        this.flightCutoffPort = flightCutoffPort;
        this.planRepository = planRepository;
        this.stopRepository = stopRepository;
        this.cronRepository = cronRepository;
        this.fleetConfigRepository = fleetConfigRepository;
        this.manifestRepository = manifestRepository;
        this.itemRepository = itemRepository;
        this.gridDataAdapter = gridDataAdapter;
        this.cronEventProducer = cronEventProducer;
        this.properties = properties;
    }

    @Override
    @Transactional
    public BindOutcome bindDelivery(UUID cityId, LocalDate date, UUID parcelId, UUID destinationHexId, Instant slaDeadline) {
        BindOutcome existing = alreadyBound(parcelId, HandoffDirection.DELIVER);
        if (existing != null) return existing;

        PlanCtx ctx = context(cityId, date);
        DaCronSchedule cron = resolveCron(ctx, ctx.hexToDa.get(destinationHexId));
        if (cron == null) {
            log.warn("Deliver parcel {} unresolved: hex {} not in any DA territory / no cron", parcelId, destinationHexId);
            return BindOutcome.unresolved(parcelId);
        }
        return bindDeliverToCron(ctx, cityId, date, parcelId, cron, slaDeadline, -1);
    }

    // Deliver: ride the soonest loop with room (FCFS, §12.3). loops ≤ afterLoopExclusive are skipped so
    // a carried/no-show parcel lands strictly after its failed loop (§13.2). slaDeadline is stored, not
    // gated — a late/null deadline still binds (the box is here and must go out).
    private BindOutcome bindDeliverToCron(PlanCtx ctx, UUID cityId, LocalDate date, UUID parcelId,
                                          DaCronSchedule cron, Instant slaDeadline, int afterLoopExclusive) {
        return bindEarliest(ctx, cityId, date, parcelId, cron.getVanId(),
                ctx.deliverSlots(cron.getVanId(), cron.getHexVertexId()),
                HandoffDirection.DELIVER, cron.getDaId(), slaDeadline, afterLoopExclusive);
    }

    // Shared fastest-greedy core (both directions): bind the earliest loop with live capacity, else
    // overflow only when every loop is full. The deadline rides onto the item for M9/M10 but never gates.
    private BindOutcome bindEarliest(PlanCtx ctx, UUID cityId, LocalDate date, UUID parcelId, UUID van,
                                     List<LoopSlot> slots, HandoffDirection direction, UUID counterpartyDaId,
                                     Instant deadline, int afterLoopExclusive) {
        for (LoopSlot slot : LoopBinder.loopsEarliestFirst(slots)) {
            if (slot.loopIndex() <= afterLoopExclusive) continue;
            VanManifest manifest = lockOrCreate(ctx, van, slot.loopIndex(), date);
            if (itemRepository.countByManifestIdAndDirection(manifest.getId(), direction) < ctx.capacity) {
                VanManifestItem item = appendItem(manifest, direction, parcelId, slot, counterpartyDaId, deadline);
                return BindOutcome.bound(parcelId, slot.loopIndex(), van, item.getId(), List.of());
            }
        }
        return overflow(cityId, van, parcelId, deadline);
    }

    @Override
    @Transactional
    public BindOutcome rebindDelivery(UUID parcelId) {
        VanManifestItem old = itemRepository.findByParcelId(parcelId).stream()
                .filter(i -> i.getDirection() == HandoffDirection.DELIVER && i.getStatus() != ManifestItemStatus.EXCEPTION)
                .findFirst()
                .orElse(null);
        if (old == null) return BindOutcome.unresolved(parcelId);

        VanManifest oldManifest = manifestRepository.findById(old.getManifestId())
                .orElseThrow(() -> new IllegalStateException("manifest missing for item " + old.getId()));
        int failedLoop = oldManifest.getLoopIndex();
        old.setStatus(ManifestItemStatus.EXCEPTION); // carried off this loop; the rebind below re-places it
        itemRepository.save(old);

        RoutePlan plan = planRepository.findById(oldManifest.getRoutePlanId())
                .orElseThrow(() -> new IllegalStateException("plan missing for manifest " + oldManifest.getId()));
        PlanCtx ctx = context(plan.getCityId(), oldManifest.getValidDate());
        DaCronSchedule cron = ctx.daToCron.get(old.getCounterpartyDaId());
        if (cron == null) return BindOutcome.unresolved(parcelId);
        return bindDeliverToCron(ctx, plan.getCityId(), oldManifest.getValidDate(), parcelId, cron,
                old.getSlaDeadline(), failedLoop);
    }

    @Override
    @Transactional
    public BindOutcome bindCollect(UUID cityId, LocalDate date, UUID parcelId, UUID daId) {
        BindOutcome existing = alreadyBound(parcelId, HandoffDirection.COLLECT);
        if (existing != null) return existing;

        PlanCtx ctx = context(cityId, date);
        DaCronSchedule cron = resolveCron(ctx, daId);
        if (cron == null) {
            log.warn("Collect parcel {} unresolved: DA {} has no cron slot", parcelId, daId);
            return BindOutcome.unresolved(parcelId);
        }
        UUID van = cron.getVanId();
        // Flight cutoff (− hub tail) is recorded on the item for M9/M10; missing cutoff → window end.
        // Advisory only: v1 binds the soonest loop back regardless, same as deliver. See §12.
        Instant deadline = flightCutoffPort.outboundFlightCutoff(cityId, date)
                .map(c -> c.minus(Duration.ofMinutes(properties.getBinding().getHubTailMinutes())))
                .orElse(ctx.windowEnd);
        return bindEarliest(ctx, cityId, date, parcelId, van,
                ctx.collectSlots(van, cron.getHexVertexId()),
                HandoffDirection.COLLECT, daId, deadline, -1);
    }

    @Override
    @Transactional
    public BindingResult reconcileDeliveries(UUID cityId, LocalDate date) {
        List<HubSortPort.ReadyForDeliveryParcel> parcels = new ArrayList<>(hubSortPort.readyForDelivery(cityId, date));
        parcels.sort(Comparator.comparing(HubSortPort.ReadyForDeliveryParcel::slaDeadline)); // SLA-first backlog
        List<BindOutcome> outcomes = parcels.stream()
                .map(p -> bindDelivery(cityId, date, p.parcelId(), p.destinationHexId(), p.slaDeadline()))
                .toList();
        return BindingResult.from(outcomes);
    }

    @Override
    @Transactional
    public BindingResult reconcileCollections(UUID cityId, LocalDate date) {
        PlanCtx ctx = context(cityId, date);
        List<BindOutcome> outcomes = new ArrayList<>();
        for (UUID daId : ctx.daToCron.keySet()) {
            for (DaAccumulationPort.AccumulatedParcel a : daAccumulationPort.collectedAwaitingPickup(daId, date)) {
                outcomes.add(bindCollect(cityId, date, a.parcelId(), daId));
            }
        }
        return BindingResult.from(outcomes);
    }

    @Override
    @Transactional
    public ReconcileSummary reconcileToLivePlan(UUID cityId, LocalDate date) {
        PlanCtx ctx = context(cityId, date); // the new live (APPROVED) plan
        // Stops the new plan still visits, keyed by the physical (van, loop, vertex) — used to decide
        // whether an already-loaded parcel can still be delivered on the van it is physically riding.
        Set<String> survivingStops = new HashSet<>();
        for (RoutePlanStop s : ctx.stops) {
            survivingStops.add(stopKey(s.getVanId(), s.getLoopIndex(), s.getHexVertexId()));
        }

        int repointed = 0, rebound = 0, kept = 0, escalated = 0;
        for (RoutePlan stale : planRepository.findByCityIdAndValidForDate(cityId, date)) {
            if (stale.getId().equals(ctx.planId)) continue; // the live plan's manifests are already correct
            for (VanManifest manifest : manifestRepository.findByRoutePlanId(stale.getId())) {
                repoint(manifest, ctx.planId);
                repointed++;
                for (VanManifestItem item : itemRepository.findByManifestId(manifest.getId())) {
                    switch (item.getStatus()) {
                        case PLANNED -> {
                            // Not yet physically loaded → free to re-route against the new plan.
                            item.setStatus(ManifestItemStatus.EXCEPTION);
                            itemRepository.save(item);
                            rebindPlanned(ctx, cityId, date, item);
                            rebound++;
                        }
                        case LOADED, ONBOARD -> {
                            // Physically on the van; never auto-moved. Keep if its stop survived, else escalate.
                            if (survivingStops.contains(stopKey(manifest.getVanId(), manifest.getLoopIndex(), item.getMeetingVertexId()))) {
                                kept++;
                            } else {
                                cronEventProducer.emitLoopOverflow(cityId, manifest.getVanId(), item.getParcelId(),
                                        manifest.getLoopIndex(), item.getSlaDeadline());
                                escalated++;
                            }
                        }
                        default -> kept++; // HANDED_OFF / RECONCILED / EXCEPTION — terminal, nothing to do
                    }
                }
            }
        }
        if (repointed > 0) {
            log.info("Reconciled day to live plan {} (city={} date={}): repointed={} reboundPlanned={} keptInCustody={} escalated={}",
                    ctx.planId, cityId, date, repointed, rebound, kept, escalated);
        }
        return new ReconcileSummary(repointed, rebound, kept, escalated);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    // Re-route a not-yet-loaded item against the live plan via its counterparty DA. The old item is
    // already EXCEPTION'd, so this appends a fresh PLANNED item on the correct loop, or escalates
    // (LOOP_OVERFLOW) if that DA has no cron slot under the new plan.
    private void rebindPlanned(PlanCtx ctx, UUID cityId, LocalDate date, VanManifestItem item) {
        if (item.getDirection() == HandoffDirection.DELIVER) {
            DaCronSchedule cron = ctx.daToCron.get(item.getCounterpartyDaId());
            if (cron == null) {
                cronEventProducer.emitLoopOverflow(cityId, null, item.getParcelId(), -1, item.getSlaDeadline());
                return;
            }
            bindDeliverToCron(ctx, cityId, date, item.getParcelId(), cron, item.getSlaDeadline(), -1);
        } else {
            bindCollect(cityId, date, item.getParcelId(), item.getCounterpartyDaId());
        }
    }

    private static String stopKey(UUID van, int loop, UUID vertex) {
        return van + "|" + loop + "|" + vertex;
    }

    private BindOutcome overflow(UUID cityId, UUID van, UUID parcelId, Instant deadline) {
        cronEventProducer.emitLoopOverflow(cityId, van, parcelId, -1, deadline);
        return BindOutcome.overflow(parcelId, van);
    }

    // Returns a BOUND outcome if the parcel already has a live item for the direction (replay-safe),
    // else null. EXCEPTION items (carried/no-show) are ignored so the parcel can be re-bound.
    private BindOutcome alreadyBound(UUID parcelId, HandoffDirection direction) {
        return itemRepository.findByParcelId(parcelId).stream()
                .filter(i -> i.getDirection() == direction && i.getStatus() != ManifestItemStatus.EXCEPTION)
                .findFirst()
                .map(i -> BindOutcome.bound(parcelId, null, null, i.getId(), List.of()))
                .orElse(null);
    }

    private DaCronSchedule resolveCron(PlanCtx ctx, UUID daId) {
        return daId == null ? null : ctx.daToCron.get(daId);
    }

    private VanManifestItem appendItem(VanManifest manifest, HandoffDirection direction, UUID parcelId,
                                       LoopSlot slot, UUID counterpartyDaId, Instant deadline) {
        return itemRepository.save(VanManifestItem.builder()
                .manifestId(manifest.getId())
                .parcelId(parcelId)
                .direction(direction)
                .stopSeq(slot.stopSeq())
                .meetingVertexId(slot.vertexId())
                .counterpartyDaId(counterpartyDaId)
                .slaDeadline(deadline)
                .status(ManifestItemStatus.PLANNED)
                .build());
    }

    // Lock the loop's manifest row (creating it if absent); the unique constraint makes create race-safe.
    private VanManifest lockOrCreate(PlanCtx ctx, UUID van, int loop, LocalDate date) {
        return manifestRepository.lockByVanLoopDate(van, loop, date).map(m -> repoint(m, ctx.planId)).orElseGet(() -> {
            try {
                manifestRepository.saveAndFlush(VanManifest.builder()
                        .routePlanId(ctx.planId).vanId(van).loopIndex(loop).validDate(date)
                        .status(ManifestStatus.BUILDING).build());
            } catch (DataIntegrityViolationException raced) {
                // another thread created it first — fall through and lock the existing row
            }
            return manifestRepository.lockByVanLoopDate(van, loop, date)
                    .map(m -> repoint(m, ctx.planId))
                    .orElseThrow(() -> new IllegalStateException("manifest vanished for van=" + van + " loop=" + loop));
        });
    }

    // Option B safety net: if this physical (van,loop,day) row was created under a now-superseded plan
    // (intraday re-plan), re-point it at the live plan rather than binding onto a stale manifest. A no-op
    // when there is one plan/day. Backstops reconcileToLivePlan for binds that race an override.
    private VanManifest repoint(VanManifest manifest, UUID livePlanId) {
        if (!manifest.getRoutePlanId().equals(livePlanId)) {
            manifest.setRoutePlanId(livePlanId);
            manifestRepository.save(manifest);
        }
        return manifest;
    }

    private PlanCtx context(UUID cityId, LocalDate date) {
        // Resolve the live APPROVED plan first (cheap, indexed) and key the cache by its id — a
        // re-approval/replan produces a new plan id, so we miss the cache and rebuild rather than
        // binding against a superseded plan's stale stops + territory map.
        RoutePlan plan = planRepository.findByCityIdAndValidForDateAndStatus(cityId, date, RoutePlanStatus.APPROVED)
                .stream().max(Comparator.comparingInt(RoutePlan::getRevision))
                .orElseThrow(() -> new IllegalStateException("No APPROVED route plan for city=" + cityId + " date=" + date));
        return contextCache.computeIfAbsent(plan.getId().toString(), k -> buildContext(plan, cityId, date));
    }

    private PlanCtx buildContext(RoutePlan plan, UUID cityId, LocalDate date) {
        int capacity = fleetConfigRepository.findByCityId(cityId)
                .orElseThrow(() -> new IllegalStateException("No fleet config for city=" + cityId))
                .getCapacityPackets();
        Map<UUID, UUID> hexToDa = gridDataAdapter.hexToDa(cityId, date);
        Map<UUID, DaCronSchedule> daToCron = new HashMap<>();
        cronRepository.findByRoutePlanId(plan.getId()).forEach(c -> daToCron.put(c.getDaId(), c));
        List<RoutePlanStop> stops = stopRepository.findByRoutePlanId(plan.getId());
        Instant windowEnd = atDate(date, LocalTime.of(properties.getWindow().getEndHour(), 0));
        return new PlanCtx(plan.getId(), date, capacity, hexToDa, daToCron, stops, windowEnd,
                properties.getBinding().getHubReturnMinutes());
    }

    private static Instant atDate(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(ClockConfig.IST).toInstant();
    }

    // Immutable per-day plan lookups. Capacity is checked live against the DB, not from here.
    private record PlanCtx(UUID planId, LocalDate date, int capacity, Map<UUID, UUID> hexToDa,
                           Map<UUID, DaCronSchedule> daToCron, List<RoutePlanStop> stops,
                           Instant windowEnd, int hubReturnMinutes) {

        List<LoopSlot> deliverSlots(UUID van, UUID vertex) {
            List<LoopSlot> out = new ArrayList<>();
            for (RoutePlanStop s : stops) {
                if (s.getVanId().equals(van) && vertex.equals(s.getHexVertexId())) {
                    out.add(new LoopSlot(s.getLoopIndex(), van, vertex, s.getStopSeq(), atDate(date, s.getPlannedArrival()), null));
                }
            }
            return out;
        }

        List<LoopSlot> collectSlots(UUID van, UUID vertex) {
            List<LoopSlot> out = new ArrayList<>();
            for (RoutePlanStop s : stops) {
                if (s.getVanId().equals(van) && vertex.equals(s.getHexVertexId())) {
                    Instant hubReturn = atDate(date, lastDeparture(van, s.getLoopIndex())).plus(Duration.ofMinutes(hubReturnMinutes));
                    out.add(new LoopSlot(s.getLoopIndex(), van, vertex, s.getStopSeq(), atDate(date, s.getPlannedArrival()), hubReturn));
                }
            }
            return out;
        }

        LocalTime lastDeparture(UUID van, int loop) {
            return stops.stream()
                    .filter(s -> s.getVanId().equals(van) && s.getLoopIndex() == loop)
                    .map(RoutePlanStop::getPlannedDeparture)
                    .max(Comparator.naturalOrder())
                    .orElse(LocalTime.of(0, 0));
        }
    }
}
