package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.CityLogisticsNode;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.ProvisioningFlag;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanSource;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.model.DaTerritory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HUB_RETURN mode (the M6 gate off, M6-D-020): a city with no van/meeting-point infrastructure.
 * There is no van route to solve — the DA collects in their territory and periodically returns to
 * the hub. This produces the minimal thing M5's hard-constraint engine needs: a per-DA cron whose
 * rendezvous is the <b>hub</b> and whose "meeting times" are a <b>fixed periodic cadence</b>
 * (every {@code hubReturnIntervalMinutes}). No CVRP, no meeting-point set-cover, no shuttle.
 *
 * <p>Unlike VAN_MEETING (which yields a PROPOSED plan an ops user approves), a hub-return plan has
 * nothing to review, so it is written APPROVED and its {@code DA_CRON_SCHEDULED} events fire
 * immediately — carrying the hub coordinates so M5 gates each pickup on "reach the hub by the next
 * return slot" (its feasibility path is coordinate-agnostic, so no M5 change is needed).</p>
 */
@Service
public class HubReturnScheduleService {

    private static final Logger log = LoggerFactory.getLogger(HubReturnScheduleService.class);

    /** Fallback cadence when a HUB_RETURN city has no explicit {@code hub_return_interval_minutes}. */
    static final int DEFAULT_INTERVAL_MINUTES = 180;

    private final GridDataAdapter gridDataAdapter;
    private final RoutePlanRepository routePlanRepository;
    private final DaCronScheduleRepository daCronScheduleRepository;
    private final CronEventProducer cronEventProducer;
    private final RoutingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    HubReturnScheduleService(GridDataAdapter gridDataAdapter,
                             RoutePlanRepository routePlanRepository,
                             DaCronScheduleRepository daCronScheduleRepository,
                             CronEventProducer cronEventProducer,
                             RoutingProperties properties,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.gridDataAdapter = gridDataAdapter;
        this.routePlanRepository = routePlanRepository;
        this.daCronScheduleRepository = daCronScheduleRepository;
        this.cronEventProducer = cronEventProducer;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Build (or rebuild) the day's hub-return schedule for a gated city. Supersedes any prior plan
     * for the same city/date, writes an APPROVED plan + one cron per active DA (hub as rendezvous),
     * and emits {@code DA_CRON_SCHEDULED} with the hub coordinates.
     */
    @Transactional
    public RoutePlan planHubReturn(UUID cityId, LocalDate date, CityFleetConfig fleet, CityLogisticsNode hub) {
        supersedeExisting(cityId, date);

        int interval = fleet.getHubReturnIntervalMinutes() != null
                ? fleet.getHubReturnIntervalMinutes() : DEFAULT_INTERVAL_MINUTES;
        List<String> meetingTimes = hubReturnTimes(interval);
        String meetingTimesJson = serialize(meetingTimes);

        List<DaTerritory> territories = gridDataAdapter.getDaTerritories(cityId, date);

        UUID planId = UUID.randomUUID();
        RoutePlan plan = RoutePlan.builder()
                .id(planId)
                .cityId(cityId)
                .validForDate(date)
                .status(RoutePlanStatus.APPROVED)   // nothing to review — deterministic config
                .source(RoutePlanSource.NIGHTLY)
                .solverType(RoutingSolverType.SAVINGS)
                .revision(1)
                .vansUsed(0)
                .recommendedVanCount(0)
                .provisioningFlag(ProvisioningFlag.OK)
                .nLoops(meetingTimes.size())
                .realisedCycleMinutes(interval)
                .notes("HUB_RETURN mode: DA returns to hub every " + interval + "min (" + meetingTimes + ")")
                .approvedAt(Instant.now(clock))
                .build();
        routePlanRepository.save(plan);

        // hex_vertex_id has no FK; the hub node id is a stable non-null sentinel (M5 uses the event's
        // lat/lon, not this id, for feasibility). van_id is null — there is no van.
        List<DaCronSchedule> crons = new ArrayList<>(territories.size());
        for (DaTerritory t : territories) {
            crons.add(DaCronSchedule.builder()
                    .routePlanId(planId)
                    .daId(t.daId())
                    .hexVertexId(hub.getId())
                    .vanId(null)
                    .meetingTimes(meetingTimesJson)
                    .cityId(cityId)
                    .validDate(date)
                    .build());
        }
        if (!crons.isEmpty()) daCronScheduleRepository.saveAll(crons);

        for (DaCronSchedule cron : crons) {
            cronEventProducer.emitDaCronScheduled(cron, hub.getLat(), hub.getLon());
        }

        log.info("HUB_RETURN plan {} for cityId={} date={}: {} DA(s), returns at {}",
                planId, cityId, date, crons.size(), meetingTimes);
        return plan;
    }

    private void supersedeExisting(UUID cityId, LocalDate date) {
        for (RoutePlan p : routePlanRepository.findByCityIdAndValidForDate(cityId, date)) {
            if (p.getStatus() == RoutePlanStatus.PROPOSED || p.getStatus() == RoutePlanStatus.APPROVED) {
                p.setStatus(RoutePlanStatus.SUPERSEDED);
                routePlanRepository.save(p);
            }
        }
    }

    /** Periodic hub-return slots ("HH:mm") within the operating window, one every {@code interval} min. */
    List<String> hubReturnTimes(int interval) {
        int start = properties.getWindow().getStartHour() * 60;
        int end = properties.getWindow().getEndHour() * 60;
        List<String> times = new ArrayList<>();
        // First return is one interval into the shift (the DA collects before their first drop);
        // last is at/before shift end. Always yield at least the shift-end slot.
        for (int m = start + interval; m <= end; m += interval) {
            times.add(LocalTime.of(m / 60, m % 60).toString());
        }
        if (times.isEmpty()) {
            times.add(LocalTime.of(end / 60, end % 60).toString());
        }
        return times;
    }

    private String serialize(List<String> times) {
        try {
            return objectMapper.writeValueAsString(times);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize hub-return meeting times", e);
        }
    }
}
