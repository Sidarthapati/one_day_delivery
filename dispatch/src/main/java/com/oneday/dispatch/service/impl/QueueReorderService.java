package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Re-orders a DA's QUEUED tasks by a weighted distance + wait-time (aging) score so a geometrically
 * isolated task can't starve: the longer it waits the higher it climbs, until — past
 * {@code ageSaturationMinutes} — it outranks near-but-fresh tasks. The IN_PROGRESS prefix (what the DA
 * is doing / heading to) is never moved.
 *
 * <p><b>v1 scope:</b> only DAs <em>without</em> an active cron are reordered. For cron DAs the
 * cron-meeting deadline is a hard constraint and the assign path already places tasks by cheapest
 * (distance-aware) insertion; free-form reordering there is deferred to v2.</p>
 */
@Service
public class QueueReorderService {

    private static final Logger log = LoggerFactory.getLogger(QueueReorderService.class);

    private final DispatchQueueRepository queueRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final DaEventProducer daEventProducer;
    private final DispatchProperties props;

    QueueReorderService(DispatchQueueRepository queueRepository,
                        DaCronAssignmentRepository cronRepository,
                        DaStatusService daStatusService,
                        DaEventProducer daEventProducer,
                        DispatchProperties props) {
        this.queueRepository = queueRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.daEventProducer = daEventProducer;
        this.props = props;
    }

    /** Re-score + re-sequence a DA's QUEUED tail. Caller holds the DA lock. Persists + rebuilds mirror. */
    @Transactional
    public void reorder(UUID daId, LocalDate date) {
        if (!props.getReorder().isEnabled()) {
            return;
        }
        // Cron DAs keep the cron-safe insertion order (v1).
        DaCronAssignment cron = cronRepository.findByDaIdAndOperatingDate(daId, date).orElse(null);
        if (cronActive(cron)) {
            return;
        }

        List<DispatchQueue> active = new java.util.ArrayList<>(queueRepository
                .findByDaIdAndOperatingDateAndStatusIn(daId, date, List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS)));
        active.sort(Comparator.comparingInt(DispatchQueue::getQueuePosition));

        List<DispatchQueue> inProgress = active.stream()
                .filter(q -> q.getStatus() == TaskStatus.IN_PROGRESS).toList();
        List<DispatchQueue> queued = active.stream()
                .filter(q -> q.getStatus() == TaskStatus.QUEUED).toList();
        if (queued.size() <= 1) {
            return;   // nothing to reorder
        }

        double[] anchor = anchor(inProgress, daId);
        Instant now = Instant.now();
        List<DispatchQueue> reordered = queued.stream()
                .sorted(Comparator.comparingDouble((DispatchQueue q) -> priority(q, anchor, now)).reversed())
                .toList();

        if (sameOrder(queued, reordered)) {
            return;   // already optimal — no churn
        }

        int pos = inProgress.size();
        for (DispatchQueue q : reordered) {
            q.setQueuePosition(pos++);
        }
        queueRepository.saveAll(reordered);
        QueueMirror.rebuild(daStatusService, queueRepository, daId, date);
        daEventProducer.emitQueueReordered(daId, queued.get(0).getCityId());
        log.debug("Reordered {} queued tasks for DA {}", queued.size(), daId);
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

    /** Where the DA is heading: its in-progress stop, else its last GPS, else none. */
    private double[] anchor(List<DispatchQueue> inProgress, UUID daId) {
        if (!inProgress.isEmpty()) {
            DispatchQueue head = inProgress.get(inProgress.size() - 1);
            return new double[] {head.getTaskLat(), head.getTaskLon()};
        }
        DaLiveStatus live = daStatusService.getLiveStatus(daId);
        if (live != null && live.getLat() != null && live.getLon() != null) {
            return new double[] {live.getLat(), live.getLon()};
        }
        return null;
    }

    private static boolean sameOrder(List<DispatchQueue> a, List<DispatchQueue> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getShipmentId().equals(b.get(i).getShipmentId())) {
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
}
