package com.oneday.sla.service.impl;

import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.sla.domain.SlaAction;
import com.oneday.sla.domain.SlaActionType;
import com.oneday.sla.domain.SlaEscalation;
import com.oneday.sla.domain.SlaShipment;
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
    private final ShipmentContactPort contactPort;
    private final WeatherService weatherService;

    public SlaQueryServiceImpl(SlaShipmentRepository shipmentRepo, SlaLegRepository legRepo,
                               SlaEscalationRepository escalationRepo, SlaActionRepository actionRepo,
                               CourierOnShipmentPort courierPort, ShipmentContactPort contactPort,
                               WeatherService weatherService) {
        this.shipmentRepo = shipmentRepo;
        this.legRepo = legRepo;
        this.escalationRepo = escalationRepo;
        this.actionRepo = actionRepo;
        this.courierPort = courierPort;
        this.contactPort = contactPort;
        this.weatherService = weatherService;
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
                        courierPort.forShipment(ss.getShipmentId()).orElse(null),
                        contacts.get(ss.getShipmentId()),
                        weatherExposed(ss, adverse)))
                .toList();
        return new SlaControlTowerResponse(p, s, result.getTotalElements(), items);
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
                courierPort.forShipment(ss.getShipmentId()).orElse(null),
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
