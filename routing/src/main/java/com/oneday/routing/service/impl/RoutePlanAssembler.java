package com.oneday.routing.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.service.model.MeetingPlan;
import com.oneday.routing.service.model.RouteStop;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.SolveResult;
import com.oneday.routing.service.model.VanRoute;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Stage 4 (periodise) + Stage 5 (derive) of the pipeline (§7.4–7.5, M6-D-003/-008), kept pure so it
 * is testable without a DB or the solver. Turns one solved loop into {@code n_loops} repetitions
 * stamped to wall-clock times, then derives each DA's meeting-time list at its vertex.
 *
 * <p>Cadence (loop period) = {@code max(realisedCycle, cronFreezeMinutes)} so consecutive meeting
 * times are always ≥ M5's freeze window apart (C6); {@code n_loops = floor(window / cadence)}.
 */
final class RoutePlanAssembler {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private RoutePlanAssembler() {}

    record Assembly(int nLoops, int realisedCycleMinutes, List<RoutePlanStop> stops, List<DaCronSchedule> crons) {}

    static Assembly assemble(UUID routePlanId, UUID cityId, LocalDate date,
                             SolveResult result, List<RoutingNode> nodes, MeetingPlan plan,
                             int windowStartHour, int windowMinutes, int dwellMinutes,
                             int cronFreezeMinutes, ObjectMapper mapper, IntFunction<UUID> vanIdFn) {
        if (result.routes().isEmpty()) {
            return new Assembly(0, 0, List.of(), List.of());
        }

        long realisedCycleSeconds = result.routes().stream().mapToLong(VanRoute::spanSeconds).max().orElse(0);
        int realisedCycleMinutes = (int) Math.ceil(realisedCycleSeconds / 60.0);
        int cadenceMinutes = Math.max(realisedCycleMinutes, cronFreezeMinutes);
        long cadenceSeconds = (long) cadenceMinutes * 60;
        int nLoops = Math.max(1, windowMinutes / cadenceMinutes);
        LocalTime windowStart = LocalTime.of(windowStartHour, 0);
        long dwellSeconds = (long) dwellMinutes * 60;

        List<RoutePlanStop> stops = new ArrayList<>();
        // Per vertex (recorded from loop 0): its arrival offset, serving van — for cron derivation.
        Map<UUID, Long> vertexOffset = new HashMap<>();
        Map<UUID, UUID> vertexVan = new HashMap<>();

        for (VanRoute route : result.routes()) {
            UUID vanId = vanIdFn.apply(route.vanIndex());
            for (int loop = 0; loop < nLoops; loop++) {
                long loopStart = loop * cadenceSeconds;
                int seq = 1;
                for (RouteStop stop : route.stops()) {
                    RoutingNode node = nodes.get(stop.nodeIndex());
                    LocalTime arrival = windowStart.plusSeconds(loopStart + stop.arrivalOffsetSeconds());
                    LocalTime departure = arrival.plusSeconds(dwellSeconds);
                    stops.add(RoutePlanStop.builder()
                            .routePlanId(routePlanId)
                            .vanId(vanId)
                            .loopIndex(loop)
                            .stopSeq(seq++)
                            .nodeKind(StopNodeKind.MEETING_VERTEX)
                            .hexVertexId(stop.vertexId())
                            .plannedArrival(arrival)
                            .plannedDeparture(departure)
                            .deliverQty(node.deliverQty())
                            .collectQty(node.collectQty())
                            .loadAfter(stop.loadAfter())
                            .build());
                    if (loop == 0) {
                        vertexOffset.put(stop.vertexId(), stop.arrivalOffsetSeconds());
                        vertexVan.put(stop.vertexId(), vanId);
                    }
                }
            }
        }

        List<DaCronSchedule> crons = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : plan.daToVertex().entrySet()) {
            UUID daId = e.getKey();
            UUID vertexId = e.getValue();
            Long offset = vertexOffset.get(vertexId);
            if (offset == null) continue; // DA's vertex not on any route (shouldn't happen if covered)

            List<String> meetingTimes = new ArrayList<>(nLoops);
            for (int loop = 0; loop < nLoops; loop++) {
                meetingTimes.add(windowStart.plusSeconds(loop * cadenceSeconds + offset).format(HHMM));
            }
            crons.add(DaCronSchedule.builder()
                    .routePlanId(routePlanId)
                    .daId(daId)
                    .hexVertexId(vertexId)
                    .vanId(vertexVan.get(vertexId))
                    .meetingTimes(writeJson(mapper, meetingTimes))
                    .cityId(cityId)
                    .validDate(date)
                    .build());
        }

        return new Assembly(nLoops, realisedCycleMinutes, stops, crons);
    }

    private static String writeJson(ObjectMapper mapper, List<String> times) {
        try {
            return mapper.writeValueAsString(times);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize meeting times", e);
        }
    }
}
