package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Purges append-only {@code da_gps_ping} trail rows older than
 * {@code dispatch.gps.trail-retention-days}, so the route history stays bounded (the table is
 * otherwise append-only and grows forever). The live position lives in {@code da_status} as a
 * single overwrite-in-place row and is untouched — this only trims the trail. Runs daily off-peak.
 */
@Component
public class DaGpsPingRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DaGpsPingRetentionJob.class);

    private final DaGpsPingRepository repository;
    private final DispatchProperties props;

    public DaGpsPingRetentionJob(DaGpsPingRepository repository, DispatchProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Scheduled(cron = "${dispatch.gps.trail-purge-cron:0 30 3 * * *}",
               zone = "${dispatch.shift.zone:Asia/Kolkata}")
    public void run() {
        purge(Instant.now());
    }

    /** Package-visible so a test can drive it with a fixed clock. Returns rows deleted. */
    int purge(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(props.getGps().getTrailRetentionDays()));
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purged {} da_gps_ping trail rows older than {}", deleted, cutoff);
        }
        return deleted;
    }
}
