package com.oneday.routing.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.service.model.MeetingPlan;
import com.oneday.routing.service.model.RouteStop;
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
 * is testable without a DB or the solver. Turns each solved loop into repetitions stamped to
 * wall-clock times, then derives each DA's meeting-time list at its vertex.
 *
 * <p>Cadence is <b>per van</b>: a van repeats its own loop every {@code max(itsSpan, cronFreeze)} and
 * sweeps as many times as the window allows, so a near-hub van is not throttled to the slowest van.
 * M5 reads each van's stamped times into the DA's queue, so the fleet need not share one cadence.
 * Each vertex's daily demand is therefore split by the serving van's own loop count.
 */
final class RoutePlanAssembler {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final double[] ZERO_DEMAND = {0, 0};

    private RoutePlanAssembler() {}

    record Assembly(int nLoops, int realisedCycleMinutes, List<RoutePlanStop> stops, List<DaCronSchedule> crons) {}

    static Assembly assemble(UUID routePlanId, UUID cityId, LocalDate date,
                             SolveResult result, MeetingPlan plan, Map<UUID, double[]> dailyByVertex,
                             int windowStartHour, int windowMinutes, int dwellMinutes,
                             int cronFreezeMinutes, ObjectMapper mapper, IntFunction<UUID> vanIdFn) {
        if (result.routes().isEmpty()) {
            return new Assembly(0, 0, List.of(), List.of());
        }

        long windowSeconds = (long) windowMinutes * 60;
        long cronFreezeSeconds = (long) cronFreezeMinutes * 60;
        LocalTime windowStart = LocalTime.of(windowStartHour, 0);
        long dwellSeconds = (long) dwellMinutes * 60;

        List<RoutePlanStop> stops = new ArrayList<>();
        Map<UUID, Long> vertexOffset = new HashMap<>();   // within-loop arrival offset (from loop 0)
        Map<UUID, UUID> vertexVan = new HashMap<>();
        Map<UUID, Long> vertexCadence = new HashMap<>();
        Map<UUID, Integer> vertexLoops = new HashMap<>();

        int maxLoops = 0;
        long maxSpanSeconds = 0;

        for (VanRoute route : result.routes()) {
            UUID vanId = vanIdFn.apply(route.vanIndex());
            long cadenceSeconds = Math.max(route.spanSeconds(), cronFreezeSeconds);
            int vanLoops = (int) Math.max(1, windowSeconds / cadenceSeconds);
            maxLoops = Math.max(maxLoops, vanLoops);
            maxSpanSeconds = Math.max(maxSpanSeconds, route.spanSeconds());

            List<int[]> perStop = perLoopQuantities(route, dailyByVertex, vanLoops);

            for (int loop = 0; loop < vanLoops; loop++) {
                long loopStart = loop * cadenceSeconds;
                int seq = 1;
                for (int i = 0; i < route.stops().size(); i++) {
                    RouteStop stop = route.stops().get(i);
                    int[] q = perStop.get(i);
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
                            .deliverQty(q[0])
                            .collectQty(q[1])
                            .loadAfter(q[2])
                            .build());
                    if (loop == 0) {
                        vertexOffset.put(stop.vertexId(), stop.arrivalOffsetSeconds());
                        vertexVan.put(stop.vertexId(), vanId);
                        vertexCadence.put(stop.vertexId(), cadenceSeconds);
                        vertexLoops.put(stop.vertexId(), vanLoops);
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

            long cadenceSeconds = vertexCadence.get(vertexId);
            int loops = vertexLoops.get(vertexId);
            List<String> meetingTimes = new ArrayList<>(loops);
            for (int loop = 0; loop < loops; loop++) {
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

        int realisedCycleMinutes = (int) Math.ceil(maxSpanSeconds / 60.0);
        return new Assembly(maxLoops, realisedCycleMinutes, stops, crons);
    }

    /** Per-loop [deliver, collect, loadAfter] for each of the van's stops: daily demand split by the
     *  van's own loop count, with load walked from the deliveries it leaves the hub carrying. */
    private static List<int[]> perLoopQuantities(VanRoute route, Map<UUID, double[]> dailyByVertex, int vanLoops) {
        int n = route.stops().size();
        int[] deliver = new int[n];
        int[] collect = new int[n];
        int load = 0;
        for (int i = 0; i < n; i++) {
            double[] daily = dailyByVertex.getOrDefault(route.stops().get(i).vertexId(), ZERO_DEMAND);
            deliver[i] = (int) Math.ceil(daily[0] / vanLoops);
            collect[i] = (int) Math.ceil(daily[1] / vanLoops);
            load += deliver[i];
        }
        List<int[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            load = load - deliver[i] + collect[i];
            out.add(new int[]{deliver[i], collect[i], load});
        }
        return out;
    }

    private static String writeJson(ObjectMapper mapper, List<String> times) {
        try {
            return mapper.writeValueAsString(times);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize meeting times", e);
        }
    }
}
