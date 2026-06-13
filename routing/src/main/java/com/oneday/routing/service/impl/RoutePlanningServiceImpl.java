package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.CityLogisticsNode;
import com.oneday.routing.domain.LogisticsNodeKind;
import com.oneday.routing.domain.ProvisioningFlag;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanSource;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.CityLogisticsNodeRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.service.DemandAggregationService;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.MeetingPointSelectionService;
import com.oneday.routing.service.RoutePlanningService;
import com.oneday.routing.service.TravelMatrixService;
import com.oneday.routing.service.VanRouteSolver;
import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.MeetingPlan;
import com.oneday.routing.service.model.MeetingVertex;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.TerritoryDemand;
import com.oneday.routing.service.model.TravelMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements the §7 pipeline. Builds the OSRM matrix once, then runs a small fixed-point on
 * {@code n_loops} (per-loop demand = daily / n_loops, M6-D-003) since the realised cycle that sets
 * {@code n_loops} only emerges from the solve. A second fleet-sizing pass finds the minimum vans to
 * hold the 2–3h cycle target and flags {@code UNDER_PROVISIONED} (M6-D-005). The constrained pass
 * that ships relaxes the cycle bound to the full operating window — a loop physically cannot exceed
 * it — so a short fleet still yields a (longer-loop, fewer-cycle) plan rather than nothing.
 */
@Service
class RoutePlanningServiceImpl implements RoutePlanningService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanningServiceImpl.class);
    private static final int MAX_NLOOPS_ITERATIONS = 3;

    private final GridDataAdapter gridDataAdapter;
    private final DemandAggregationService demandAggregationService;
    private final MeetingPointSelectionService meetingPointSelectionService;
    private final TravelMatrixService travelMatrixService;
    private final VanRouteSolver solver;
    private final CityFleetConfigRepository fleetConfigRepository;
    private final CityLogisticsNodeRepository logisticsNodeRepository;
    private final RoutePlanRepository routePlanRepository;
    private final RoutePlanStopRepository routePlanStopRepository;
    private final DaCronScheduleRepository daCronScheduleRepository;
    private final RoutingProperties properties;
    private final ObjectMapper objectMapper;

    RoutePlanningServiceImpl(GridDataAdapter gridDataAdapter,
                             DemandAggregationService demandAggregationService,
                             MeetingPointSelectionService meetingPointSelectionService,
                             TravelMatrixService travelMatrixService,
                             VanRouteSolver solver,
                             CityFleetConfigRepository fleetConfigRepository,
                             CityLogisticsNodeRepository logisticsNodeRepository,
                             RoutePlanRepository routePlanRepository,
                             RoutePlanStopRepository routePlanStopRepository,
                             DaCronScheduleRepository daCronScheduleRepository,
                             RoutingProperties properties,
                             ObjectMapper objectMapper) {
        this.gridDataAdapter = gridDataAdapter;
        this.demandAggregationService = demandAggregationService;
        this.meetingPointSelectionService = meetingPointSelectionService;
        this.travelMatrixService = travelMatrixService;
        this.solver = solver;
        this.fleetConfigRepository = fleetConfigRepository;
        this.logisticsNodeRepository = logisticsNodeRepository;
        this.routePlanRepository = routePlanRepository;
        this.routePlanStopRepository = routePlanStopRepository;
        this.daCronScheduleRepository = daCronScheduleRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public RoutePlan plan(UUID cityId, LocalDate date) {
        CityFleetConfig fleet = fleetConfigRepository.findByCityId(cityId)
                .orElseThrow(() -> new IllegalStateException("No city_fleet_config for cityId=" + cityId));
        CityLogisticsNode hub = logisticsNodeRepository.findByCityIdAndKind(cityId, LogisticsNodeKind.HUB)
                .orElseThrow(() -> new IllegalStateException("No HUB logistics node for cityId=" + cityId));

        List<DaTerritory> territories = gridDataAdapter.getDaTerritories(cityId, date);
        Map<UUID, TerritoryDemand> demandByDa = demandAggregationService.aggregate(territories).stream()
                .collect(Collectors.toMap(TerritoryDemand::daId, d -> d));
        MeetingPlan meetingPlan = meetingPointSelectionService.select(territories);

        int windowMinutes = (properties.getWindow().getEndHour() - properties.getWindow().getStartHour()) * 60;

        if (meetingPlan.vertices().isEmpty()) {
            log.info("No meeting vertices for cityId={} date={} (no active territories) — empty plan", cityId, date);
            return persistPlan(cityId, date, RoutingSolverType.OR_TOOLS, 0, 0, 0, 0,
                    ProvisioningFlag.OK, "No active territories", List.of(), List.of());
        }

        // OSRM is called once: the node coordinates are fixed; only per-loop quantities change.
        long[][] seconds = travelMatrixService.buildMatrix(buildNodes(hub, meetingPlan, demandByDa, 1)).travelSeconds();

        // Fixed-point on n_loops: the realised cycle that sets n_loops only emerges from the solve.
        int nLoops = Math.max(1, windowMinutes / fleet.getCycleTimeMaxMinutes());
        SolveResult constrained = null;
        List<RoutingNode> nodes = null;
        for (int iter = 0; iter < MAX_NLOOPS_ITERATIONS; iter++) {
            nodes = buildNodes(hub, meetingPlan, demandByDa, nLoops);
            TravelMatrix matrix = new TravelMatrix(nodes, seconds);
            constrained = solver.solve(matrix, fleet.getVansAvailable(), fleet.getCapacityPackets(), windowMinutes);
            if (!constrained.feasible() || constrained.routes().isEmpty()) break;

            long maxSpan = constrained.routes().stream().mapToLong(r -> r.spanSeconds()).max().orElse(0);
            int cadence = Math.max((int) Math.ceil(maxSpan / 60.0), properties.getCronFreezeMinutes());
            int newNLoops = Math.max(1, windowMinutes / cadence);
            if (newNLoops == nLoops) break;
            nLoops = newNLoops;
        }

        Integer recommended = recommendVanCount(nodes, seconds, fleet);
        ProvisioningFlag flag = (recommended != null && fleet.getVansAvailable() < recommended)
                ? ProvisioningFlag.UNDER_PROVISIONED : ProvisioningFlag.OK;
        if (flag == ProvisioningFlag.UNDER_PROVISIONED) {
            log.warn("City {} under-provisioned for {}: {} vans available, {} recommended",
                    cityId, date, fleet.getVansAvailable(), recommended);
        }

        if (constrained == null || !constrained.feasible() || constrained.routes().isEmpty()) {
            String note = "No feasible routing with " + fleet.getVansAvailable() + " vans"
                    + (recommended != null ? " (recommended " + recommended + ")" : "");
            RoutingSolverType engine = constrained != null ? constrained.solverType() : RoutingSolverType.OR_TOOLS;
            return persistPlan(cityId, date, engine, 0, 0, 0, recommended,
                    ProvisioningFlag.UNDER_PROVISIONED, note, List.of(), List.of());
        }

        UUID planId = UUID.randomUUID(); // assign up front so assembled children reference it
        RoutePlanAssembler.Assembly assembly = RoutePlanAssembler.assemble(
                planId, cityId, date, constrained, nodes, meetingPlan,
                properties.getWindow().getStartHour(), windowMinutes, fleet.getDwellMinutes(),
                properties.getCronFreezeMinutes(), objectMapper, idx -> vanId(cityId, idx));

        int vansUsed = constrained.routes().size();
        return persistPlanWithId(planId, cityId, date, constrained.solverType(),
                vansUsed, assembly.nLoops(), assembly.realisedCycleMinutes(), recommended, flag, null,
                assembly.stops(), assembly.crons());
    }

    /** Min vans to cover all vertices within capacity AND the 2–3h cycle target (M6-D-005). */
    private Integer recommendVanCount(List<RoutingNode> nodes, long[][] seconds, CityFleetConfig fleet) {
        if (nodes == null || nodes.size() <= 1) return 0;
        int vertexCount = nodes.size() - 1;
        int totalDeliver = nodes.stream().mapToInt(RoutingNode::deliverQty).sum();
        int lowerBound = Math.max(1, (int) Math.ceil((double) totalDeliver / fleet.getCapacityPackets()));
        TravelMatrix matrix = new TravelMatrix(nodes, seconds);
        for (int k = lowerBound; k <= vertexCount; k++) {
            SolveResult r = solver.solve(matrix, k, fleet.getCapacityPackets(), fleet.getCycleTimeMaxMinutes());
            if (r.feasible() && !r.routes().isEmpty()) return k;
        }
        return vertexCount; // even one-van-per-vertex couldn't hold the cycle target — best effort
    }

    private List<RoutingNode> buildNodes(CityLogisticsNode hub, MeetingPlan plan,
                                         Map<UUID, TerritoryDemand> demandByDa, int nLoops) {
        List<RoutingNode> nodes = new ArrayList<>(plan.vertices().size() + 1);
        // Hub carries the per-loop turnaround (unload + reload) so it counts against the cycle.
        nodes.add(RoutingNode.hub(hub.getId(), hub.getLat(), hub.getLon(),
                properties.getHubTurnaroundMinutes() * 60));
        int dwellSeconds = properties.getDwellMinutes() * 60;
        int idx = 1;
        for (MeetingVertex vertex : plan.vertices()) {
            double dailyDeliver = 0, dailyCollect = 0;
            for (UUID daId : plan.vertexToDaIds().getOrDefault(vertex.vertexId(), List.of())) {
                TerritoryDemand d = demandByDa.get(daId);
                if (d == null) continue;
                dailyDeliver += d.lastMileQty();
                dailyCollect += d.firstMileQty();
            }
            int perLoopDeliver = (int) Math.ceil(dailyDeliver / nLoops);
            int perLoopCollect = (int) Math.ceil(dailyCollect / nLoops);
            nodes.add(new RoutingNode(idx, StopNodeKind.MEETING_VERTEX, vertex.vertexId(),
                    vertex.lat(), vertex.lon(), perLoopDeliver, perLoopCollect, dwellSeconds));
            idx++;
        }
        return nodes;
    }

    /** Deterministic van id per (city, vehicle index) — no fleet registry exists yet. */
    private static UUID vanId(UUID cityId, int vanIndex) {
        return UUID.nameUUIDFromBytes(("van:" + cityId + ":" + vanIndex).getBytes(StandardCharsets.UTF_8));
    }

    private RoutePlan persistPlan(UUID cityId, LocalDate date, RoutingSolverType solverType, int vansUsed, int nLoops,
                                  int realisedCycleMinutes, Integer recommended, ProvisioningFlag flag, String notes,
                                  List<RoutePlanStop> stops, List<DaCronSchedule> crons) {
        return persistPlanWithId(UUID.randomUUID(), cityId, date, solverType, vansUsed, nLoops,
                realisedCycleMinutes, recommended, flag, notes, stops, crons);
    }

    private RoutePlan persistPlanWithId(UUID planId, UUID cityId, LocalDate date, RoutingSolverType solverType,
                                        int vansUsed, int nLoops, int realisedCycleMinutes, Integer recommended,
                                        ProvisioningFlag flag, String notes,
                                        List<RoutePlanStop> stops, List<DaCronSchedule> crons) {
        RoutePlan plan = RoutePlan.builder()
                .id(planId)
                .cityId(cityId)
                .validForDate(date)
                .status(RoutePlanStatus.PROPOSED)
                .source(RoutePlanSource.NIGHTLY)
                .solverType(solverType)
                .revision(1)
                .vansUsed(vansUsed)
                .recommendedVanCount(recommended)
                .provisioningFlag(flag)
                .nLoops(nLoops)
                .realisedCycleMinutes(realisedCycleMinutes)
                .notes(notes)
                .build();
        routePlanRepository.save(plan);
        if (!stops.isEmpty()) routePlanStopRepository.saveAll(stops);
        if (!crons.isEmpty()) daCronScheduleRepository.saveAll(crons);
        return plan;
    }
}
