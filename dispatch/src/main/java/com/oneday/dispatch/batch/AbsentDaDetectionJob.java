package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Marks a DA {@code ABSENT} when its GPS heartbeat has lapsed past
 * {@code dispatch.da.absent-threshold-minutes}, and emits {@code DA_ABSENT} so M10/M11 can escalate
 * and M3 can re-cover the hex. Heartbeat resumption flips the DA back to {@code IDLE} inside
 * {@code DaStatusService.updateGps}.
 */
@Component
public class AbsentDaDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(AbsentDaDetectionJob.class);

    private final DaStatusService daStatusService;
    private final DaEventProducer eventProducer;
    private final DispatchProperties props;

    public AbsentDaDetectionJob(DaStatusService daStatusService, DaEventProducer eventProducer,
                                DispatchProperties props) {
        this.daStatusService = daStatusService;
        this.eventProducer = eventProducer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${dispatch.monitor.interval-seconds:300}", timeUnit = TimeUnit.SECONDS)
    public void run() {
        sweep(Instant.now());
    }

    /** Package-visible for direct testing with a fixed clock. */
    void sweep(Instant now) {
        Duration threshold = Duration.ofMinutes(props.getDa().getAbsentThresholdMinutes());
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaStatusEnum status = daStatusService.getStatus(daId);
            // OFFLINE (never pinged) and already-ABSENT DAs are not re-flagged.
            if (status == null || status == DaStatusEnum.OFFLINE || status == DaStatusEnum.ABSENT) {
                continue;
            }
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            Instant last = live != null ? live.getLastHeartbeat() : null;
            if (last == null) {
                continue;   // no heartbeat to measure against yet
            }
            if (Duration.between(last, now).compareTo(threshold) > 0) {
                daStatusService.updateStatus(daId, DaStatusEnum.ABSENT);
                eventProducer.emitDaAbsent(daId, live.getCityId());
                log.warn("DA {} marked ABSENT (silent {} min)", daId, Duration.between(last, now).toMinutes());
            }
        }
    }
}
