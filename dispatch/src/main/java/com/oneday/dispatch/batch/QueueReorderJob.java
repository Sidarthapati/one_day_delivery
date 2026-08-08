package com.oneday.dispatch.batch;

import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.impl.QueueReorderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * Slow safety tick: reorder normally runs on queue-changing events (insert / drop / task completion);
 * this periodic re-score exists only for the time-based effects no event triggers — the ≥ aging-
 * saturation starvation promotion and cron-slack shrinking as the cutoff nears. No-op outside shift
 * hours (no DAs loaded). Runs for cron DAs too (reorder keeps the cron cutoff feasible).
 */
@Component
public class QueueReorderJob {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DaStatusService daStatusService;
    private final QueueReorderService queueReorderService;

    public QueueReorderJob(DaStatusService daStatusService, QueueReorderService queueReorderService) {
        this.daStatusService = daStatusService;
        this.queueReorderService = queueReorderService;
    }

    @Scheduled(fixedDelayString = "${dispatch.reorder.tick-seconds:300}", timeUnit = TimeUnit.SECONDS)
    public void tick() {
        LocalDate today = LocalDate.now(IST);
        for (var daId : daStatusService.loadedDaIds()) {
            // Serialize with assignment/queue mutation via the per-DA lock.
            daStatusService.withDaLock(daId, () -> {
                queueReorderService.reorder(daId, today);
                return null;
            });
        }
    }
}
