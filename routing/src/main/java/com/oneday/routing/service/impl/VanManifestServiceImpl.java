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
import java.util.List;
import java.util.Map;
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

    // ── helpers ───────────────────────────────────────────────────────────

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
        return manifestRepository.lockByVanLoopDate(van, loop, date).orElseGet(() -> {
            try {
                manifestRepository.saveAndFlush(VanManifest.builder()
                        .routePlanId(ctx.planId).vanId(van).loopIndex(loop).validDate(date)
                        .status(ManifestStatus.BUILDING).build());
            } catch (DataIntegrityViolationException raced) {
                // another thread created it first — fall through and lock the existing row
            }
            return manifestRepository.lockByVanLoopDate(van, loop, date)
                    .orElseThrow(() -> new IllegalStateException("manifest vanished for van=" + van + " loop=" + loop));
        });
    }

    private PlanCtx context(UUID cityId, LocalDate date) {
        return contextCache.computeIfAbsent(cityId + "|" + date, k -> buildContext(cityId, date));
    }

    private PlanCtx buildContext(UUID cityId, LocalDate date) {
        RoutePlan plan = planRepository.findByCityIdAndValidForDateAndStatus(cityId, date, RoutePlanStatus.APPROVED)
                .stream().max(Comparator.comparingInt(RoutePlan::getRevision))
                .orElseThrow(() -> new IllegalStateException("No APPROVED route plan for city=" + cityId + " date=" + date));
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
