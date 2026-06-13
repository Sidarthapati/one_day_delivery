package com.oneday.routing.api;

import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.dto.FleetConfigResponse;
import com.oneday.routing.dto.FleetConfigUpdateRequest;
import com.oneday.routing.dto.LogisticsNodeResponse;
import com.oneday.routing.dto.RouteStopGeoResponse;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.RoutePlanLifecycleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fleet config + map-support reads for the planning console (§17.2/§17.3). {@code city_fleet_config}
 * is the only mutable M6 table (van count / cycle knobs ops edits nightly); the node + enriched-stop
 * GETs back the map view (hub/airport markers, road-snapped van polylines).
 */
@RestController
@RequestMapping("/routing")
public class RoutingFleetController {

    private final CityFleetConfigRepository fleetRepository;
    private final CityLogisticsNodeRepository nodeRepository;
    private final RoutePlanLifecycleService lifecycleService;
    private final RoutePlanStopRepository stopRepository;
    private final GridDataAdapter gridDataAdapter;
    private final Clock clock;

    RoutingFleetController(CityFleetConfigRepository fleetRepository,
                           CityLogisticsNodeRepository nodeRepository,
                           RoutePlanLifecycleService lifecycleService,
                           RoutePlanStopRepository stopRepository,
                           GridDataAdapter gridDataAdapter,
                           Clock clock) {
        this.fleetRepository = fleetRepository;
        this.nodeRepository = nodeRepository;
        this.lifecycleService = lifecycleService;
        this.stopRepository = stopRepository;
        this.gridDataAdapter = gridDataAdapter;
        this.clock = clock;
    }

    // ── Fleet config ───────────────────────────────────────────────────────────

    @GetMapping("/fleet/{cityId}")
    public FleetConfigResponse getFleet(@PathVariable UUID cityId) {
        return FleetConfigResponse.from(requireFleet(cityId));
    }

    @PutMapping("/fleet/{cityId}")
    @Transactional
    public FleetConfigResponse updateFleet(@PathVariable UUID cityId,
                                           @RequestBody FleetConfigUpdateRequest req) {
        CityFleetConfig fleet = requireFleet(cityId);
        if (req.vansAvailable() != null) fleet.setVansAvailable(req.vansAvailable());
        if (req.capacityPackets() != null) fleet.setCapacityPackets(req.capacityPackets());
        if (req.cycleTimeMinMinutes() != null) fleet.setCycleTimeMinMinutes(req.cycleTimeMinMinutes());
        if (req.cycleTimeMaxMinutes() != null) fleet.setCycleTimeMaxMinutes(req.cycleTimeMaxMinutes());
        if (req.shuttleCadenceMinutes() != null) fleet.setShuttleCadenceMinutes(req.shuttleCadenceMinutes());
        if (req.maxDaToVertexMinutes() != null) fleet.setMaxDaToVertexMinutes(req.maxDaToVertexMinutes());
        if (req.dwellMinutes() != null) fleet.setDwellMinutes(req.dwellMinutes());
        return FleetConfigResponse.from(fleetRepository.save(fleet));
    }

    // ── Logistics nodes (map markers) ──────────────────────────────────────────

    @GetMapping("/nodes/{cityId}")
    public List<LogisticsNodeResponse> getNodes(@PathVariable UUID cityId) {
        return nodeRepository.findByCityId(cityId).stream()
                .map(LogisticsNodeResponse::from)
                .toList();
    }

    // ── Enriched stops (all vans, with coordinates) ────────────────────────────

    @GetMapping("/plans/{cityId}/stops")
    public List<RouteStopGeoResponse> getAllStops(
            @PathVariable UUID cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(clock);
        RoutePlan plan = lifecycleService.activePlan(cityId, d)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active route plan for cityId=" + cityId + " date=" + d));
        Map<UUID, double[]> coords = gridDataAdapter.vertexCoords(cityId);
        return stopRepository.findByRoutePlanId(plan.getId()).stream()
                .map(s -> RouteStopGeoResponse.from(s, coords))
                .toList();
    }

    private CityFleetConfig requireFleet(UUID cityId) {
        return fleetRepository.findByCityId(cityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No fleet config for cityId=" + cityId));
    }
}
