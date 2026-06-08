package com.oneday.routing.batch;

import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanSource;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.service.RoutePlanningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Nightly van-route governance (§10), mirroring M3's {@code NightlyReplanJob}:
 * <ul>
 *   <li><b>01:00</b> — solve §7 per city → a PROPOSED {@link RoutePlan} for tomorrow.</li>
 *   <li><b>06:00</b> — escalation: no APPROVED plan for today ⇒ warn the station manager.</li>
 *   <li><b>07:00</b> — auto-fallback: still unapproved ⇒ copy yesterday's APPROVED plan forward.</li>
 * </ul>
 * The set of plannable cities is exactly those with a {@code city_fleet_config} row.
 */
@Component
public class NightlyRoutePlanJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyRoutePlanJob.class);

    private final CityFleetConfigRepository fleetConfigRepository;
    private final RoutePlanningService routePlanningService;
    private final RoutePlanRepository routePlanRepository;
    private final RoutePlanStopRepository routePlanStopRepository;
    private final DaCronScheduleRepository daCronScheduleRepository;
    private final Clock clock;

    NightlyRoutePlanJob(CityFleetConfigRepository fleetConfigRepository,
                        RoutePlanningService routePlanningService,
                        RoutePlanRepository routePlanRepository,
                        RoutePlanStopRepository routePlanStopRepository,
                        DaCronScheduleRepository daCronScheduleRepository,
                        Clock clock) {
        this.fleetConfigRepository = fleetConfigRepository;
        this.routePlanningService = routePlanningService;
        this.routePlanRepository = routePlanRepository;
        this.routePlanStopRepository = routePlanStopRepository;
        this.daCronScheduleRepository = daCronScheduleRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void run() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        log.info("NightlyRoutePlanJob starting for date={}", tomorrow);
        for (CityFleetConfig fleet : fleetConfigRepository.findAll()) {
            try {
                routePlanningService.plan(fleet.getCityId(), tomorrow);
            } catch (Exception e) {
                log.error("NightlyRoutePlanJob failed for cityId={}", fleet.getCityId(), e);
            }
        }
        log.info("NightlyRoutePlanJob complete for date={}", tomorrow);
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void checkEscalation() {
        LocalDate today = LocalDate.now(clock);
        for (CityFleetConfig fleet : fleetConfigRepository.findAll()) {
            UUID cityId = fleet.getCityId();
            if (!hasApproved(cityId, today)) {
                log.warn("ESCALATION_ALERT cityId={} date={}: no approved route plan by 06:00; station manager action required",
                        cityId, today);
            }
        }
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    public void applyFallbackIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        LocalDate yesterday = today.minusDays(1);
        for (CityFleetConfig fleet : fleetConfigRepository.findAll()) {
            UUID cityId = fleet.getCityId();
            if (hasApproved(cityId, today)) continue;

            List<RoutePlan> yesterdayApproved = routePlanRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, yesterday, RoutePlanStatus.APPROVED);
            if (yesterdayApproved.isEmpty()) {
                log.warn("AUTO_FALLBACK_FAILED cityId={}: no approved plan for yesterday {} either; city has no coverage",
                        cityId, yesterday);
                continue;
            }
            applyFallback(cityId, today, yesterdayApproved.get(yesterdayApproved.size() - 1));
        }
    }

    private boolean hasApproved(UUID cityId, LocalDate date) {
        return !routePlanRepository.findByCityIdAndValidForDateAndStatus(cityId, date, RoutePlanStatus.APPROVED).isEmpty();
    }

    // Copy yesterday's APPROVED plan (stops + crons) forward as today's APPROVED FALLBACK plan.
    @Transactional
    void applyFallback(UUID cityId, LocalDate today, RoutePlan yesterdayPlan) {
        UUID fallbackId = UUID.randomUUID();
        RoutePlan fallback = RoutePlan.builder()
                .id(fallbackId)
                .cityId(cityId)
                .validForDate(today)
                .status(RoutePlanStatus.APPROVED)
                .source(RoutePlanSource.FALLBACK)
                .solverType(yesterdayPlan.getSolverType())
                .revision(1)
                .supersedesPlanId(yesterdayPlan.getId())
                .vansUsed(yesterdayPlan.getVansUsed())
                .recommendedVanCount(yesterdayPlan.getRecommendedVanCount())
                .provisioningFlag(yesterdayPlan.getProvisioningFlag())
                .nLoops(yesterdayPlan.getNLoops())
                .realisedCycleMinutes(yesterdayPlan.getRealisedCycleMinutes())
                .notes("Auto-fallback: no approved plan by 07:00; copied from " + today.minusDays(1))
                .build();
        routePlanRepository.save(fallback);

        List<RoutePlanStop> stops = routePlanStopRepository.findByRoutePlanId(yesterdayPlan.getId()).stream()
                .map(s -> RoutePlanStop.builder()
                        .routePlanId(fallbackId)
                        .vanId(s.getVanId())
                        .loopIndex(s.getLoopIndex())
                        .stopSeq(s.getStopSeq())
                        .nodeKind(s.getNodeKind())
                        .hexVertexId(s.getHexVertexId())
                        .plannedArrival(s.getPlannedArrival())
                        .plannedDeparture(s.getPlannedDeparture())
                        .deliverQty(s.getDeliverQty())
                        .collectQty(s.getCollectQty())
                        .loadAfter(s.getLoadAfter())
                        .build())
                .toList();
        if (!stops.isEmpty()) routePlanStopRepository.saveAll(stops);

        List<DaCronSchedule> crons = daCronScheduleRepository.findByRoutePlanId(yesterdayPlan.getId()).stream()
                .map(c -> DaCronSchedule.builder()
                        .routePlanId(fallbackId)
                        .daId(c.getDaId())
                        .hexVertexId(c.getHexVertexId())
                        .vanId(c.getVanId())
                        .meetingTimes(c.getMeetingTimes())
                        .cityId(c.getCityId())
                        .validDate(today)
                        .build())
                .toList();
        if (!crons.isEmpty()) daCronScheduleRepository.saveAll(crons);

        log.info("AUTO_FALLBACK_APPLIED cityId={} date={} planId={} stops={} crons={}",
                cityId, today, fallbackId, stops.size(), crons.size());
    }
}
