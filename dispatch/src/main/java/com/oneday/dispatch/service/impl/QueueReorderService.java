package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.CronFeasibilityService;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.FeasibilityStop;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.LatLon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Re-orders a DA's QUEUED tasks by a weighted distance + wait-time (aging) score so a geometrically
 * isolated task can't starve: the longer it waits the higher it climbs, until — past
 * {@code ageSaturationMinutes} — it outranks near-but-fresh tasks. The IN_PROGRESS prefix (what the DA
 * is doing / heading to) is never moved.
 *
 * <p><b>Cron-aware:</b> for a DA with an active cron/van-meeting, the priority order is applied only
 * as far as it stays cron-feasible — the largest priority-ordered prefix the DA can still finish and
 * reach the cron vertex by cutoff (with a {@code cronSafetyMarginMinutes} buffer). Tasks that don't
 * fit are kept on the DA but demoted to the tail and flagged {@code beyond_cron} ("after van
 * meeting"); {@code buildFeasibility} excludes them from pre-cron slack, and they auto-promote once
 * reachable again (after the meeting / against the next loop). As the cutoff nears the feasible
 * prefix shrinks to empty — so the cron effectively gets 100% priority near the deadline.</p>
 */
@Service
public class QueueReorderService {

    private static final Logger log = LoggerFactory.getLogger(QueueReorderService.class);

    private final DispatchQueueRepository queueRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final DaEventProducer daEventProducer;
    private final CronFeasibilityService feasibilityService;
    private final DispatchProperties props;

    QueueReorderService(DispatchQueueRepository queueRepository,
                        DaCronAssignmentRepository cronRepository,
                        DaStatusService daStatusService,
                        DaEventProducer daEventProducer,
                        CronFeasibilityService feasibilityService,
                        DispatchProperties props) {
        this.queueRepository = queueRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.daEventProducer = daEventProducer;
        this.feasibilityService = feasibilityService;
        this.props = props;
    }

    /** Re-score + re-sequence a DA's QUEUED tail. Caller holds the DA lock. Persists + rebuilds mirror. */
    @Transactional
    public void reorder(UUID daId, LocalDate date) {
        if (!props.getReorder().isEnabled()) {
            return;
        }

        List<DispatchQueue> active = new ArrayList<>(queueRepository
                .findByDaIdAndOperatingDateAndStatusIn(daId, date, List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS)));
        active.sort(Comparator.comparingInt(DispatchQueue::getQueuePosition));

        List<DispatchQueue> inProgress = active.stream()
                .filter(q -> q.getStatus() == TaskStatus.IN_PROGRESS).toList();
        List<DispatchQueue> queued = active.stream()
                .filter(q -> q.getStatus() == TaskStatus.QUEUED).toList();
        if (queued.isEmpty()) {
            return;
        }

        // Where the DA is / will be next, and when — the reference point for both scoring and cron feasibility.
        double[] anchorLoc;
        Instant anchorTime;
        if (!inProgress.isEmpty()) {
            DispatchQueue head = inProgress.stream()
                    .max(Comparator.comparing(r -> r.getExpectedEta() != null ? r.getExpectedEta() : Instant.now()))
                    .orElseThrow();
            anchorLoc = new double[] {head.getTaskLat(), head.getTaskLon()};
            anchorTime = head.getExpectedEta() != null ? head.getExpectedEta() : Instant.now();
        } else {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            anchorLoc = (live != null && live.getLat() != null && live.getLon() != null)
                    ? new double[] {live.getLat(), live.getLon()} : null;
            anchorTime = Instant.now();
        }

        Instant now = Instant.now();
        List<DispatchQueue> byPriority = queued.stream()
                .sorted(Comparator.comparingDouble((DispatchQueue q) -> priority(q, anchorLoc, now)).reversed())
                .toList();

        DaCronAssignment cron = cronRepository.findByDaIdAndOperatingDate(daId, date).orElse(null);
        // Cron-constrain only when we know where the DA is (no anchor → can't assess feasibility, reorder freely).
        boolean cronActive = cronActive(cron) && anchorLoc != null;

        // Desired result: ordered tasks + whether each is parked beyond the cron. Computed without
        // mutating the entities, so the no-churn check below is accurate.
        List<Desired> desired = cronActive
                ? cronConstrained(byPriority, anchorLoc, anchorTime, cron, now)
                : byPriority.stream().map(q -> new Desired(q, false)).toList();

        if (unchanged(queued, desired)) {
            return;
        }

        int pos = inProgress.size();
        for (Desired d : desired) {
            d.task().setQueuePosition(pos++);
            d.task().setBeyondCron(d.beyondCron());
        }
        List<DispatchQueue> toSave = desired.stream().map(Desired::task).toList();
        queueRepository.saveAll(toSave);
        QueueMirror.rebuild(daStatusService, queueRepository, daId, date);
        daEventProducer.emitQueueReordered(daId, queued.get(0).getCityId());
        long beyond = desired.stream().filter(Desired::beyondCron).count();
        log.debug("Reordered {} queued tasks for DA {} ({} beyond-cron)", queued.size(), daId, beyond);
    }

    /**
     * Greedy cron-feasible prefix: take tasks in priority order, keeping each only while the whole
     * accepted sequence still reaches the cron vertex with at least the safety margin to spare. Tasks
     * that would cut it too fine are demoted (beyond-cron), in priority order among themselves.
     */
    private List<Desired> cronConstrained(List<DispatchQueue> byPriority, double[] anchorLoc,
                                          Instant anchorTime, DaCronAssignment cron, Instant now) {
        LatLon current = new LatLon(anchorLoc[0], anchorLoc[1]);
        LatLon cronVertex = new LatLon(cron.getMeetingLat(), cron.getMeetingLon());
        Instant meetingTime = CronMeetings.activeMeetingTime(cron, now, ZoneId.of(props.getShift().getZone()));
        long marginSec = props.getReorder().getCronSafetyMarginMinutes() * 60L;
        long serviceSec = props.getService().getDefaultMinutes() * 60L;
        String cityId = cron.getCityId() != null ? cron.getCityId().toString() : null;

        List<DispatchQueue> accepted = new ArrayList<>();
        List<Desired> beyond = new ArrayList<>();
        for (DispatchQueue q : byPriority) {
            List<FeasibilityStop> trial = new ArrayList<>(accepted.size() + 1);
            for (DispatchQueue a : accepted) {
                trial.add(new FeasibilityStop(new LatLon(a.getTaskLat(), a.getTaskLon()), serviceSec));
            }
            trial.add(new FeasibilityStop(new LatLon(q.getTaskLat(), q.getTaskLon()), serviceSec));
            long slack = feasibilityService.cronSlackSeconds(current, anchorTime, trial, cronVertex, meetingTime, cityId);
            if (slack >= marginSec) {
                accepted.add(q);
            } else {
                beyond.add(new Desired(q, true));
            }
        }
        List<Desired> result = new ArrayList<>(byPriority.size());
        accepted.forEach(q -> result.add(new Desired(q, false)));
        result.addAll(beyond);
        return result;
    }

    /**
     * Higher = pick sooner. Below the saturation wait it's a weighted proximity + aging score in [0,1].
     * At/after the saturation wait a task hits the <b>starvation floor</b> ({@code 1 + ageMin}) so it
     * always outranks any fresh task regardless of distance — the anti-starvation guarantee — with the
     * oldest going first.
     */
    private double priority(DispatchQueue q, double[] anchor, Instant now) {
        DispatchProperties.Reorder cfg = props.getReorder();
        Instant since = q.getAssignedAt() != null ? q.getAssignedAt() : q.getCreatedAt();
        double ageMin = since != null ? Math.max(0, ChronoUnit.MINUTES.between(since, now)) : 0;

        if (ageMin >= cfg.getAgeSaturationMinutes()) {
            return 1.0 + ageMin;   // starvation floor — above any weighted score (≤ 1.0), oldest first
        }

        double aScore = ageMin / cfg.getAgeSaturationMinutes();   // 0..1
        if (anchor == null) {
            return aScore;   // no location reference → age-only (oldest first)
        }
        double distKm = GeoDistance.km(anchor[0], anchor[1], q.getTaskLat(), q.getTaskLon());
        double dScore = 1.0 - Math.min(distKm / cfg.getMaxDistanceKm(), 1.0);
        return cfg.getDistanceWeight() * dScore + cfg.getAgeWeight() * aScore;
    }

    /** True when the desired order + beyond-cron flags already match the current queued rows. */
    private static boolean unchanged(List<DispatchQueue> current, List<Desired> desired) {
        for (int i = 0; i < current.size(); i++) {
            DispatchQueue cur = current.get(i);
            Desired des = desired.get(i);
            if (!cur.getShipmentId().equals(des.task().getShipmentId()) || cur.isBeyondCron() != des.beyondCron()) {
                return false;
            }
        }
        return true;
    }

    private static boolean cronActive(DaCronAssignment cron) {
        return cron != null && cron.getScheduledMeetingTime() != null
                && cron.getStatus() != CronAssignmentStatus.COMPLETED
                && cron.getStatus() != CronAssignmentStatus.CANCELLED
                && cron.getStatus() != CronAssignmentStatus.MISSED;
    }

    /** A queued task's target place in the reordered tail. */
    private record Desired(DispatchQueue task, boolean beyondCron) {}
}
