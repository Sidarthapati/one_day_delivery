package com.oneday.sla.service.impl;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.common.port.StageContactPort;
import com.oneday.sla.domain.PriorityBand;
import com.oneday.sla.domain.SlaAction;
import com.oneday.sla.domain.SlaActionType;
import com.oneday.sla.domain.SlaEscalation;
import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.dto.SlaClusterResponse;
import com.oneday.sla.dto.SlaControlTowerResponse;
import com.oneday.sla.dto.SlaEscalationView;
import com.oneday.sla.dto.SlaLegView;
import com.oneday.sla.dto.SlaPassRateResponse;
import com.oneday.sla.dto.SlaShipmentDetailResponse;
import com.oneday.sla.dto.SlaShipmentSummary;
import com.oneday.sla.dto.WeatherWatchResponse;
import com.oneday.sla.service.WeatherService;
import com.oneday.sla.repository.SlaActionRepository;
import com.oneday.sla.repository.SlaEscalationRepository;
import com.oneday.sla.repository.SlaLegRepository;
import com.oneday.sla.repository.SlaShipmentRepository;
import com.oneday.sla.service.SlaQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SlaQueryServiceImpl implements SlaQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SlaShipmentRepository shipmentRepo;
    private final SlaLegRepository legRepo;
    private final SlaEscalationRepository escalationRepo;
    private final SlaActionRepository actionRepo;
    private final CourierOnShipmentPort courierPort;
    private final StageContactPort stageContactPort;
    private final ShipmentContactPort contactPort;
    private final WeatherService weatherService;

    public SlaQueryServiceImpl(SlaShipmentRepository shipmentRepo, SlaLegRepository legRepo,
                               SlaEscalationRepository escalationRepo, SlaActionRepository actionRepo,
                               CourierOnShipmentPort courierPort, StageContactPort stageContactPort,
                               ShipmentContactPort contactPort, WeatherService weatherService) {
        this.shipmentRepo = shipmentRepo;
        this.legRepo = legRepo;
        this.escalationRepo = escalationRepo;
        this.actionRepo = actionRepo;
        this.courierPort = courierPort;
        this.stageContactPort = stageContactPort;
        this.contactPort = contactPort;
        this.weatherService = weatherService;
    }

    /**
     * Who to call for a parcel, by the stage it's in: the DA on the first/last mile, the hub desk while
     * it sits in a hub, the GHA desk while it moves through the airline. Empty when nothing resolves
     * (e.g. no DA assigned yet) — the row then falls back to the customer contact.
     */
    private SlaShipmentSummary.Handler handlerFor(SlaShipment ss) {
        SlaLegType leg = ss.getCurrentLeg();
        if (leg == null) {
            return null;
        }
        return switch (leg) {
            case FIRST_MILE, LAST_MILE -> courierPort.forShipment(ss.getShipmentId())
                    .map(c -> new SlaShipmentSummary.Handler(c.name(), c.phone(), c.role().name()))
                    .orElse(null);
            case ORIGIN_HUB -> toHandler(stageContactPort.hubDesk(ss.getOriginCity()));
            case DEST_HUB -> toHandler(stageContactPort.hubDesk(ss.getDestCity()));
            case ORIGIN_AIRPORT, AIR, DEST_AIRPORT -> toHandler(stageContactPort.ghaDesk());
        };
    }

    private static SlaShipmentSummary.Handler toHandler(java.util.Optional<StageContactPort.Contact> c) {
        return c.map(x -> new SlaShipmentSummary.Handler(x.name(), x.phone(), x.role())).orElse(null);
    }

    /** True when this parcel is on a ground leg heading into a currently-adverse city. */
    private boolean weatherExposed(SlaShipment ss, java.util.Set<String> adverse) {
        if (adverse.isEmpty() || !WeatherService.isWeatherExposedLeg(ss.getCurrentLeg())) return false;
        String city = WeatherService.relevantCity(ss.getCurrentLeg(), ss.getOriginCity(), ss.getDestCity());
        return city != null && adverse.contains(city);
    }

    @Override
    @Transactional(readOnly = true)
    public SlaControlTowerResponse controlTower(SlaState state, String cityScope, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<SlaShipment> result = shipmentRepo.controlTower(state, cityScope, PageRequest.of(p, s));
        // Batch the customer contacts (one findAllById); the current handler (DA) is per-row today.
        Map<UUID, ShipmentContactPort.ShipmentContact> contacts = contactPort.contactsFor(
                result.getContent().stream().map(SlaShipment::getShipmentId).collect(Collectors.toSet()));
        java.util.Set<String> adverse = weatherService.adverseCities();
        List<SlaShipmentSummary> items = result.getContent().stream()
                .map(ss -> SlaShipmentSummary.from(
                        ss,
                        handlerFor(ss),
                        contacts.get(ss.getShipmentId()),
                        weatherExposed(ss, adverse)))
                .toList();
        return new SlaControlTowerResponse(p, s, result.getTotalElements(), items);
    }

    /** The operational cause a parcel shares with its peers — the group key for the clustered queue. */
    private record Cause(String stage, String scope, String label) {}

    private static Cause causeOf(SlaShipment ss) {
        SlaLegType leg = ss.getCurrentLeg();
        if (leg == null) {
            return new Cause("PICKUP", ss.getOriginCity(), "Awaiting pickup · " + ss.getOriginCity());
        }
        return switch (leg) {
            case FIRST_MILE -> new Cause("PICKUP", ss.getOriginCity(), "Pickup · " + ss.getOriginCity());
            case LAST_MILE -> new Cause("DELIVERY", ss.getDestCity(), "Delivery · " + ss.getDestCity());
            case ORIGIN_HUB -> new Cause("HUB", ss.getOriginCity(), "Hub · " + ss.getOriginCity());
            case DEST_HUB -> new Cause("HUB", ss.getDestCity(), "Hub · " + ss.getDestCity());
            // ponytail: no flight number on sla_shipment, so air legs cluster by lane, not by flight —
            // sharpen to "AI-501 → 12 RED" once M9 flight assignment is joined onto the shipment.
            case ORIGIN_AIRPORT, AIR, DEST_AIRPORT -> {
                String lane = ss.getOriginCity() + "→" + ss.getDestCity();
                yield new Cause("AIR", lane, "Airline (GHA) · " + lane);
            }
        };
    }

    /** The single desk to call for a whole cluster: the city hub/dispatch desk, or the national GHA for air. */
    private SlaShipmentSummary.Handler deskFor(Cause c) {
        return "AIR".equals(c.stage())
                ? toHandler(stageContactPort.ghaDesk())
                : toHandler(stageContactPort.hubDesk(c.scope()));
    }

    @Override
    @Transactional(readOnly = true)
    public SlaClusterResponse clusters(String cityScope) {
        // The actionable set: open parcels that are actually at risk (not GREEN) — a healthy parcel is no fire.
        Map<Cause, List<SlaShipment>> grouped = shipmentRepo.findByClosedAtIsNull().stream()
                .filter(ss -> visible(ss, cityScope))
                .filter(ss -> ss.getOverallState() != SlaState.GREEN)
                .collect(Collectors.groupingBy(SlaQueryServiceImpl::causeOf));

        List<SlaClusterResponse.Cluster> clusters = grouped.entrySet().stream()
                .map(e -> {
                    Cause c = e.getKey();
                    List<SlaShipment> members = e.getValue().stream()
                            .sorted(Comparator.comparingDouble(SlaShipment::getPriorityScore).reversed())
                            .toList();
                    PriorityBand band = members.stream()
                            .map(SlaShipment::getBand).filter(java.util.Objects::nonNull)
                            .max(Comparator.comparingInt(PriorityBand::rank)).orElse(PriorityBand.WATCH);
                    int breached = (int) members.stream().filter(SlaShipment::isBreached).count();
                    Instant earliestActBy = members.stream()
                            .map(SlaShipment::getActByAt).filter(java.util.Objects::nonNull)
                            .min(Comparator.naturalOrder()).orElse(null);
                    List<String> refs = members.stream().map(SlaShipment::getShipmentRef).limit(50).toList();
                    return new SlaClusterResponse.Cluster(c.stage(), c.scope(), c.label(), band,
                            members.size(), breached, earliestActBy, refs, deskFor(c));
                })
                // Worst band first (never a band-jump), then bigger cluster, then soonest deadline.
                .sorted(Comparator.comparingInt((SlaClusterResponse.Cluster cl) -> cl.band().rank()).reversed()
                        .thenComparing(Comparator.comparingInt(SlaClusterResponse.Cluster::size).reversed())
                        .thenComparing(cl -> cl.earliestActBy() == null ? Instant.MAX : cl.earliestActBy()))
                .toList();
        return new SlaClusterResponse(clusters);
    }

    @Override
    @Transactional(readOnly = true)
    public SlaShipmentDetailResponse detail(String shipmentRef, String cityScope) {
        SlaShipment ss = shipmentRepo.findByShipmentRef(shipmentRef)
                .filter(s -> visible(s, cityScope))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SLA not found"));
        List<SlaLegView> legs = legRepo.findByShipmentIdOrderBySeqAsc(ss.getShipmentId()).stream()
                .map(SlaLegView::from).toList();
        List<SlaEscalationView> escalations = escalationRepo
                .findByShipmentIdOrderByCreatedAtDesc(ss.getShipmentId()).stream()
                .map(this::toEscalationView).toList();
        SlaShipmentSummary summary = SlaShipmentSummary.from(
                ss,
                handlerFor(ss),
                contactPort.contactsFor(List.of(ss.getShipmentId())).get(ss.getShipmentId()),
                weatherExposed(ss, weatherService.adverseCities()));
        return new SlaShipmentDetailResponse(summary, legs, escalations);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlaEscalationView> redQueue(String cityScope) {
        List<SlaShipment> open = shipmentRepo.openByStates(List.of(SlaState.RED, SlaState.BREACHED), cityScope);
        return open.stream()
                .map(ss -> escalationRepo.findFirstByShipmentIdOrderByCreatedAtDesc(ss.getShipmentId()).orElse(null))
                .filter(e -> e != null)
                .map(this::toEscalationView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SlaPassRateResponse passRate(Instant from, Instant to, String cityScope) {
        long closed = shipmentRepo.countClosedBetween(from, to, cityScope);
        long breached = shipmentRepo.countBreachedBetween(from, to, cityScope);
        return SlaPassRateResponse.of(from, to, cityScope, closed, breached);
    }

    @Override
    @Transactional(readOnly = true)
    public WeatherWatchResponse weatherWatch(String cityScope) {
        Map<String, WeatherService.CityWeather> weather = weatherService.current();
        java.util.Set<String> adverse = weatherService.adverseCities();
        if (adverse.isEmpty()) {
            return new WeatherWatchResponse(List.of());
        }
        // Count open, weather-exposed parcels per adverse city (respecting the caller's city scope).
        Map<String, Long> exposedByCity = shipmentRepo.findByClosedAtIsNull().stream()
                .filter(ss -> cityScope == null
                        || cityScope.equals(ss.getOriginCity()) || cityScope.equals(ss.getDestCity()))
                .filter(ss -> weatherExposed(ss, adverse))
                .collect(Collectors.groupingBy(
                        ss -> WeatherService.relevantCity(ss.getCurrentLeg(), ss.getOriginCity(), ss.getDestCity()),
                        Collectors.counting()));

        List<WeatherWatchResponse.CityAdvisory> advisories = adverse.stream()
                .filter(city -> cityScope == null || cityScope.equals(city))
                .map(city -> {
                    WeatherService.CityWeather w = weather.get(city);
                    return new WeatherWatchResponse.CityAdvisory(
                            city,
                            w != null ? w.condition() : "Adverse",
                            w != null ? w.tempC() : Double.NaN,
                            exposedByCity.getOrDefault(city, 0L).intValue());
                })
                .sorted((a, b) -> Integer.compare(b.exposedCount(), a.exposedCount()))
                .toList();
        return new WeatherWatchResponse(advisories);
    }

    @Override
    @Transactional
    public void acknowledge(UUID escalationId, String cityScope, String userId, String role, String notes) {
        record(escalationId, cityScope, SlaActionType.ACKNOWLEDGE, userId, role, notes);
    }

    @Override
    @Transactional
    public void resolve(UUID escalationId, String cityScope, String userId, String role, String notes) {
        record(escalationId, cityScope, SlaActionType.RESOLVE, userId, role, notes);
    }

    private void record(UUID escalationId, String cityScope, SlaActionType type,
                        String userId, String role, String notes) {
        SlaEscalation esc = escalationRepo.findById(escalationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Escalation not found"));
        if (cityScope != null && !cityScope.equals(esc.getCity())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Escalation not found");
        }
        SlaAction action = new SlaAction();
        action.setEscalationId(escalationId);
        action.setShipmentId(esc.getShipmentId());
        action.setAction(type);
        action.setActedBy(userId);
        action.setActedByRole(role);
        action.setNotes(notes);
        actionRepo.save(action);
    }

    private SlaEscalationView toEscalationView(SlaEscalation e) {
        boolean acknowledged = actionRepo.existsByEscalationIdAndAction(e.getId(), SlaActionType.ACKNOWLEDGE);
        boolean resolved = actionRepo.existsByEscalationIdAndAction(e.getId(), SlaActionType.RESOLVE);
        return SlaEscalationView.from(e, acknowledged, resolved);
    }

    private boolean visible(SlaShipment s, String cityScope) {
        return cityScope == null
                || cityScope.equals(s.getOriginCity())
                || cityScope.equals(s.getDestCity());
    }
}
