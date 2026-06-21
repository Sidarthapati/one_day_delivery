package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Freezes a DA when its cron meeting is imminent: within {@code dispatch.cron.freeze-minutes} of the
 * scheduled meeting, the DA flips to {@code CRON_LOCKED} so the assignment engine stops handing it new
 * work that could make it miss the van. The {@code CRON_LOCKED → AT_CRON} step is GPS-driven (in
 * {@code DaStatusService.updateGps}), not here.
 */
@Component
public class CronMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(CronMonitorJob.class);

    /** Statuses that are already terminal-for-locking — never re-evaluated here. */
    private static final Set<DaStatusEnum> SKIP =
            EnumSet.of(DaStatusEnum.OFFLINE, DaStatusEnum.CRON_LOCKED, DaStatusEnum.AT_CRON, DaStatusEnum.ABSENT);

    private final DaStatusService daStatusService;
    private final DispatchProperties props;

    public CronMonitorJob(DaStatusService daStatusService, DispatchProperties props) {
        this.daStatusService = daStatusService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${dispatch.monitor.interval-seconds:300}", timeUnit = TimeUnit.SECONDS)
    public void run() {
        sweep(Instant.now());
    }

    /** Package-visible for direct testing with a fixed clock. */
    void sweep(Instant now) {
        Duration freeze = Duration.ofMinutes(props.getCron().getFreezeMinutes());
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaStatusEnum status = daStatusService.getStatus(daId);
            if (status == null || SKIP.contains(status)) {
                continue;
            }
            DaQueue q = daStatusService.getQueue(daId);
            DaCronAssignment cron = q != null ? q.getCron() : null;
            if (cron == null || cron.getScheduledMeetingTime() == null) {
                continue;
            }
            Duration remaining = Duration.between(now, cron.getScheduledMeetingTime());
            // Within the freeze window (meeting still ahead, or just reached) → lock.
            if (remaining.compareTo(freeze) <= 0 && !remaining.isNegative()) {
                daStatusService.updateStatus(daId, DaStatusEnum.CRON_LOCKED);
                log.info("DA {} CRON_LOCKED ({} min to meeting)", daId, remaining.toMinutes());
            }
        }
    }
}
