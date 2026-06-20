package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.CronFeasibilityService;
import com.oneday.dispatch.service.FeasibilityRequest;
import com.oneday.dispatch.service.FeasibilityResult;
import com.oneday.dispatch.service.FeasibilityStop;
import com.oneday.dispatch.service.OsrmRoutingPort;
import com.oneday.dispatch.service.model.LatLon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Cheapest-insertion cron-feasibility engine (design §8–§9). Pure function: every input arrives on
 * the {@link FeasibilityRequest}; the only collaborators are immutable {@link DispatchProperties} and
 * the stateless {@link OsrmRoutingPort}, so the service is thread-safe and exhaustively testable.
 *
 * <p>Travel time is a haversine estimate scaled by {@code roadFactor} and divided by
 * {@code avgSpeedKmph}; the {@code × 3600} converts hours to <b>seconds</b>. OSRM is consulted only
 * for borderline decisions (arrival within the confirm threshold of the cutoff); if it is
 * unavailable the engine recomputes with a conservative road factor rather than block the
 * assignment.</p>
 */
@Service
class CronFeasibilityServiceImpl implements CronFeasibilityService {

    private static final Logger log = LoggerFactory.getLogger(CronFeasibilityServiceImpl.class);

    private final DispatchProperties props;
    private final OsrmRoutingPort osrm;

    CronFeasibilityServiceImpl(DispatchProperties props, OsrmRoutingPort osrm) {
        this.props = props;
        this.osrm = osrm;
    }

    @Override
    public FeasibilityResult checkFeasibility(FeasibilityRequest req) {
        DispatchProperties.Travel travel = props.getTravel();
        double fastFactor = travel.getRoadFactor();
        long confirmThresholdSeconds = props.getOsrm().getConfirmThresholdMinutes() * 60L;

        // Seconds available between "now" (or the IN_PROGRESS task's expected completion) and the cutoff.
        long slackSeconds = Duration.between(req.currentTime(), req.scheduledMeetingTime()).getSeconds();

        // Route nodes WITHOUT the new task: current position → each queued stop → cron vertex.
        List<LatLon> nodes = routeNodes(req);
        int n = req.existingQueue().size();              // candidate insertion indices: 0..n

        // Service time is constant across insertion positions, so it never affects which slot is cheapest.
        long fixedService = totalServiceSeconds(req) + req.newTask().serviceSeconds();
        long baseTravelFast = routeTravelSeconds(nodes, fastFactor);

        // Cheapest insertion == earliest cron arrival (the only k-dependent term is extra travel),
        // so the single cheapest slot is also the most likely to be feasible — find it once.
        int bestK = 0;
        long bestExtra = Long.MAX_VALUE;
        for (int k = 0; k <= n; k++) {
            long extra = extraTravelSeconds(nodes.get(k), nodes.get(k + 1),
                    req.newTask().location(), fastFactor);
            if (extra < bestExtra) {
                bestExtra = extra;
                bestK = k;
            }
        }

        long arrivalFast = baseTravelFast + bestExtra + fixedService;
        boolean borderline = Math.abs(slackSeconds - arrivalFast) < confirmThresholdSeconds;

        if (!borderline) {
            // Clearly feasible or clearly infeasible — no need to pay for an OSRM round trip.
            return result(arrivalFast <= slackSeconds, bestK, slackSeconds - arrivalFast, bestExtra, false);
        }

        // Borderline: confirm the chosen insertion against real road durations.
        List<LatLon> path = insert(nodes, bestK + 1, req.newTask().location());
        OptionalLong osrmTravel = osrm.routeDurationSeconds(path);
        if (osrmTravel.isPresent()) {
            long arrival = osrmTravel.getAsLong() + fixedService;
            log.debug("OSRM confirmed borderline insertion: arrival {}s vs slack {}s", arrival, slackSeconds);
            return result(arrival <= slackSeconds, bestK, slackSeconds - arrival, bestExtra, true);
        }

        // OSRM unavailable (breaker open) → conservative haversine so we never optimistically accept.
        double consFactor = fastFactor * travel.getBreakerFallbackMultiplier();
        long baseTravelCons = routeTravelSeconds(nodes, consFactor);
        long extraCons = extraTravelSeconds(nodes.get(bestK), nodes.get(bestK + 1),
                req.newTask().location(), consFactor);
        long arrivalCons = baseTravelCons + extraCons + fixedService;
        log.debug("OSRM unavailable; conservative arrival {}s vs slack {}s", arrivalCons, slackSeconds);
        return result(arrivalCons <= slackSeconds, bestK, slackSeconds - arrivalCons, extraCons, false);
    }

    private static List<LatLon> routeNodes(FeasibilityRequest req) {
        List<LatLon> nodes = new ArrayList<>(req.existingQueue().size() + 2);
        nodes.add(req.currentPosition());
        for (FeasibilityStop stop : req.existingQueue()) {
            nodes.add(stop.location());
        }
        nodes.add(req.cronVertex());
        return nodes;
    }

    private static long totalServiceSeconds(FeasibilityRequest req) {
        long total = 0;
        for (FeasibilityStop stop : req.existingQueue()) {
            total += stop.serviceSeconds();
        }
        return total;
    }

    /** Sum of leg travel times along the node list, in seconds. */
    private long routeTravelSeconds(List<LatLon> nodes, double factor) {
        long total = 0;
        for (int i = 0; i < nodes.size() - 1; i++) {
            total += travelSeconds(nodes.get(i), nodes.get(i + 1), factor);
        }
        return total;
    }

    /** Added travel from splicing {@code via} between {@code left} and {@code right}. */
    private long extraTravelSeconds(LatLon left, LatLon right, LatLon via, double factor) {
        return travelSeconds(left, via, factor)
                + travelSeconds(via, right, factor)
                - travelSeconds(left, right, factor);
    }

    /** Haversine distance scaled to road seconds: km × roadFactor ÷ km/h × 3600 (hours → seconds). */
    private long travelSeconds(LatLon from, LatLon to, double factor) {
        double km = GeoDistance.km(from.lat(), from.lon(), to.lat(), to.lon());
        return Math.round((km * factor / props.getTravel().getAvgSpeedKmph()) * 3600);
    }

    private static List<LatLon> insert(List<LatLon> nodes, int index, LatLon point) {
        List<LatLon> copy = new ArrayList<>(nodes);
        copy.add(index, point);
        return copy;
    }

    private static FeasibilityResult result(boolean feasible, int bestK, long slack,
                                            long extraTravel, boolean usedOsrm) {
        return new FeasibilityResult(feasible, bestK, slack, extraTravel, usedOsrm);
    }
}
