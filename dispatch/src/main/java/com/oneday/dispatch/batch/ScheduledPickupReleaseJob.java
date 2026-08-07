package com.oneday.dispatch.batch;

import com.oneday.dispatch.service.ScheduledPickupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Promotes scheduled/off-hours pickups into the DA queue once they're due (~60 min before the slot),
 * via the normal assignment pipeline. Runs every {@code dispatch.pickup.release-scan-seconds}.
 */
@Component
public class ScheduledPickupReleaseJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPickupReleaseJob.class);

    private final ScheduledPickupService scheduledPickupService;

    public ScheduledPickupReleaseJob(ScheduledPickupService scheduledPickupService) {
        this.scheduledPickupService = scheduledPickupService;
    }

    @Scheduled(fixedDelayString = "${dispatch.pickup.release-scan-seconds:900}", timeUnit = TimeUnit.SECONDS)
    public void run() {
        int released = scheduledPickupService.releaseDue();
        if (released > 0) {
            log.info("Released {} scheduled pickup(s) into the assignment pipeline", released);
        }
    }
}
