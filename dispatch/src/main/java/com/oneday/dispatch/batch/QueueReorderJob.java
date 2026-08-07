package com.oneday.dispatch.batch;

import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.impl.QueueReorderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * Periodic re-score so aging keeps promoting stale tasks even when no new order arrives. No-op outside
 * shift hours (no DAs loaded). Reorder itself skips cron DAs.
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

    @Scheduled(fixedDelayString = "${dispatch.reorder.tick-seconds:180}", timeUnit = TimeUnit.SECONDS)
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
