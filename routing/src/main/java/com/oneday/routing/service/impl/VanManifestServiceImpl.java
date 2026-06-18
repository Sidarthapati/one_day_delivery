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
import com.oneday.routing.service.model.BindingResult;
import com.oneday.routing.service.model.BindingResult.ParcelBinding;
import com.oneday.routing.service.model.LoopSlot;
import com.oneday.routing.service.port.DaAccumulationPort;
import com.oneday.routing.service.port.FlightCutoffPort;
import com.oneday.routing.service.port.HubSortPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.OptionalInt;
import java.util.UUID;

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
    public BindingResult bindDeliveries(UUID cityId, LocalDate date) {
        PlanContext ctx = load(cityId, date);
        Duration daDelivery = Duration.ofMinutes(properties.getBinding().getDaDeliveryMinutes());

        List<HubSortPort.ReadyForDeliveryParcel> parcels = new ArrayList<>(hubSortPort.readyForDelivery(cityId));
        parcels.sort(Comparator.comparing(HubSortPort.ReadyForDeliveryParcel::slaDeadline)); // SLA-first (§12.3)

        List<ParcelBinding> bound = new ArrayList<>();
        List<ParcelBinding> overflow = new ArrayList<>();
        List<ParcelBinding> unresolved = new ArrayList<>();

        for (HubSortPort.ReadyForDeliveryParcel p : parcels) {
            UUID daId = ctx.hexToDa.get(p.destinationHexId());
            DaCronSchedule cron = daId == null ? null : ctx.daToCron.get(daId);
            if (cron == null) {
                log.warn("Deliver parcel {} unresolved: hex {} → da {} has no cron slot", p.parcelId(), p.destinationHexId(), daId);
                unresolved.add(ParcelBinding.unresolved(p.parcelId()));
                continue;
            }
            UUID van = cron.getVanId();
            List<LoopSlot> slots = ctx.deliverSlots(van, cron.getHexVertexId());
            OptionalInt loop = LoopBinder.earliestDeliverLoop(slots, p.slaDeadline(), daDelivery,
                    l -> ctx.remaining(van, l, HandoffDirection.DELIVER) > 0);
            if (loop.isEmpty()) {
                cronEventProducer.emitLoopOverflow(cityId, van, p.parcelId(), -1, p.slaDeadline());
                overflow.add(ParcelBinding.overflow(p.parcelId(), van));
                continue;
            }
            LoopSlot slot = ctx.slot(slots, loop.getAsInt());
            VanManifestItem item = ctx.appendItem(this, HandoffDirection.DELIVER, p.parcelId(), slot, daId, p.slaDeadline());
            bound.add(ParcelBinding.bound(p.parcelId(), slot.loopIndex(), van, item.getId()));
        }
        return new BindingResult(bound, overflow, unresolved);
    }

    @Override
    @Transactional
    public BindingResult bindCollections(UUID cityId, LocalDate date) {
        PlanContext ctx = load(cityId, date);
        Instant deadline = flightCutoffPort.outboundFlightCutoff(cityId, date)
                .map(c -> c.minus(Duration.ofMinutes(properties.getBinding().getHubTailMinutes())))
                .orElse(ctx.windowEnd); // no cutoff known → only the window bounds the latest loop

        record Candidate(UUID parcelId, DaCronSchedule cron, Instant pickedUpAt) {}
        List<Candidate> candidates = new ArrayList<>();
        for (DaCronSchedule cron : ctx.daToCron.values()) {
            for (DaAccumulationPort.AccumulatedParcel a : daAccumulationPort.collectedAwaitingPickup(cron.getDaId(), date)) {
                candidates.add(new Candidate(a.parcelId(), cron, a.pickedUpAt()));
            }
        }
        candidates.sort(Comparator.comparing(Candidate::pickedUpAt)); // earliest-picked first

        List<ParcelBinding> bound = new ArrayList<>();
        List<ParcelBinding> overflow = new ArrayList<>();

        for (Candidate c : candidates) {
            UUID van = c.cron().getVanId();
            List<LoopSlot> slots = ctx.collectSlots(van, c.cron().getHexVertexId());
            OptionalInt loop = LoopBinder.latestCollectLoop(slots, deadline,
                    l -> ctx.remaining(van, l, HandoffDirection.COLLECT) > 0);
            if (loop.isEmpty()) {
                cronEventProducer.emitLoopOverflow(cityId, van, c.parcelId(), -1, deadline);
                overflow.add(ParcelBinding.overflow(c.parcelId(), van));
                continue;
            }
            LoopSlot slot = ctx.slot(slots, loop.getAsInt());
            VanManifestItem item = ctx.appendItem(this, HandoffDirection.COLLECT, c.parcelId(), slot, c.cron().getDaId(), deadline);
            bound.add(ParcelBinding.bound(c.parcelId(), slot.loopIndex(), van, item.getId()));
        }
        return new BindingResult(bound, overflow, List.of());
    }

    // get-or-create the (van, loop, date) manifest in BUILDING, then append a PLANNED item.
    private VanManifestItem persistItem(PlanContext ctx, HandoffDirection direction, UUID parcelId,
                                        LoopSlot slot, UUID counterpartyDaId, Instant slaDeadline) {
        VanManifest manifest = manifestRepository
                .findByVanIdAndLoopIndexAndValidDate(slot.vanId(), slot.loopIndex(), ctx.date)
                .orElseGet(() -> manifestRepository.save(VanManifest.builder()
                        .routePlanId(ctx.plan.getId())
                        .vanId(slot.vanId())
                        .loopIndex(slot.loopIndex())
                        .validDate(ctx.date)
                        .status(ManifestStatus.BUILDING)
                        .build()));
        return itemRepository.save(VanManifestItem.builder()
                .manifestId(manifest.getId())
                .parcelId(parcelId)
                .direction(direction)
                .stopSeq(slot.stopSeq())
                .meetingVertexId(slot.vertexId())
                .counterpartyDaId(counterpartyDaId)
                .slaDeadline(slaDeadline)
                .status(ManifestItemStatus.PLANNED)
                .build());
    }

    private PlanContext load(UUID cityId, LocalDate date) {
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
        return new PlanContext(plan, date, capacity, hexToDa, daToCron, stops, windowEnd,
                properties.getBinding().getHubReturnMinutes());
    }

    private static Instant atDate(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(ClockConfig.IST).toInstant();
    }

    // Per-call lookups over the locked plan + live capacity counters.
    private static final class PlanContext {
        final RoutePlan plan;
        final LocalDate date;
        final int capacity;
        final Map<UUID, UUID> hexToDa;
        final Map<UUID, DaCronSchedule> daToCron;
        final List<RoutePlanStop> stops;
        final Instant windowEnd;
        final int hubReturnMinutes;
        final Map<String, Integer> used = new HashMap<>(); // (van|loop|dir) → bound count

        PlanContext(RoutePlan plan, LocalDate date, int capacity, Map<UUID, UUID> hexToDa,
                    Map<UUID, DaCronSchedule> daToCron, List<RoutePlanStop> stops, Instant windowEnd, int hubReturnMinutes) {
            this.plan = plan;
            this.date = date;
            this.capacity = capacity;
            this.hexToDa = hexToDa;
            this.daToCron = daToCron;
            this.stops = stops;
            this.windowEnd = windowEnd;
            this.hubReturnMinutes = hubReturnMinutes;
        }

        List<LoopSlot> deliverSlots(UUID van, UUID vertex) {
            List<LoopSlot> out = new ArrayList<>();
            for (RoutePlanStop s : stops) {
                if (s.getVanId().equals(van) && vertex.equals(s.getHexVertexId())) {
                    out.add(new LoopSlot(s.getLoopIndex(), van, vertex, s.getStopSeq(),
                            atDate(date, s.getPlannedArrival()), null));
                }
            }
            return out;
        }

        List<LoopSlot> collectSlots(UUID van, UUID vertex) {
            List<LoopSlot> out = new ArrayList<>();
            for (RoutePlanStop s : stops) {
                if (s.getVanId().equals(van) && vertex.equals(s.getHexVertexId())) {
                    Instant hubReturn = atDate(date, lastDeparture(van, s.getLoopIndex())).plus(Duration.ofMinutes(hubReturnMinutes));
                    out.add(new LoopSlot(s.getLoopIndex(), van, vertex, s.getStopSeq(),
                            atDate(date, s.getPlannedArrival()), hubReturn));
                }
            }
            return out;
        }

        // Latest planned departure among a van's stops on a loop — the loop's last vertex before hub.
        LocalTime lastDeparture(UUID van, int loop) {
            return stops.stream()
                    .filter(s -> s.getVanId().equals(van) && s.getLoopIndex() == loop)
                    .map(RoutePlanStop::getPlannedDeparture)
                    .max(Comparator.naturalOrder())
                    .orElse(LocalTime.of(0, 0));
        }

        LoopSlot slot(List<LoopSlot> slots, int loopIndex) {
            return slots.stream().filter(s -> s.loopIndex() == loopIndex).findFirst().orElseThrow();
        }

        int remaining(UUID van, int loop, HandoffDirection dir) {
            return capacity - used.getOrDefault(key(van, loop, dir), 0);
        }

        VanManifestItem appendItem(VanManifestServiceImpl svc, HandoffDirection dir, UUID parcelId,
                                   LoopSlot slot, UUID counterpartyDaId, Instant deadline) {
            VanManifestItem item = svc.persistItem(this, dir, parcelId, slot, counterpartyDaId, deadline);
            used.merge(key(slot.vanId(), slot.loopIndex(), dir), 1, Integer::sum);
            return item;
        }

        private static String key(UUID van, int loop, HandoffDirection dir) {
            return van + "|" + loop + "|" + dir;
        }
    }
}
