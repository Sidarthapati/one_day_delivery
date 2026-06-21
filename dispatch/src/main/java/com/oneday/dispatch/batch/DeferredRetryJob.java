package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.service.AssignmentOutcome;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Re-attempts PENDING deferred dispatches for cities with an active shift (design §16 / §7.3). A
 * successful retry is flipped to ASSIGNED by {@link DispatchService#reassignDeferred}; a still-infeasible
 * one has its {@code retry_after} pushed out and {@code retry_count} bumped, and is ESCALATED to M11
 * (TASK_DEFERRED_SHIFT_ENDED) once it passes {@code dispatch.deferred.max-retries}. No-op outside shift
 * hours (no DAs loaded).
 */
@Component
public class DeferredRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DeferredRetryJob.class);

    private final DispatchService dispatchService;
    private final DeferredDispatchRepository deferredRepository;
    private final DaStatusService daStatusService;
    private final DaEventProducer daEventProducer;
    private final DispatchProperties props;

    public DeferredRetryJob(DispatchService dispatchService,
                            DeferredDispatchRepository deferredRepository,
                            DaStatusService daStatusService,
                            DaEventProducer daEventProducer,
                            DispatchProperties props) {
        this.dispatchService = dispatchService;
        this.deferredRepository = deferredRepository;
        this.daStatusService = daStatusService;
        this.daEventProducer = daEventProducer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${dispatch.monitor.interval-seconds:300}", timeUnit = TimeUnit.SECONDS)
    @Transactional
    public void retry() {
        if (daStatusService.loadedDaIds().isEmpty()) {
            return;   // outside shift hours
        }
        Instant now = Instant.now();
        for (UUID cityId : citiesOnShift()) {
            for (DeferredDispatch deferred : deferredRepository.findPendingForRetry(cityId, now)) {
                AssignmentResult result = dispatchService.reassignDeferred(deferred.getId());
                if (result.outcome() == AssignmentOutcome.DEFERRED) {
                    handleFailedRetry(deferred, now);
                }
            }
        }
    }

    private void handleFailedRetry(DeferredDispatch deferred, Instant now) {
        deferred.setRetryCount(deferred.getRetryCount() + 1);
        if (deferred.getRetryCount() >= props.getDeferred().getMaxRetries()) {
            deferred.setStatus("ESCALATED");
            deferred.setEscalatedAt(now);
            deferredRepository.save(deferred);
            daEventProducer.emitTaskDeferredShiftEnded(null, deferred.getCityId(), deferred.getShipmentId());
            log.info("Deferred dispatch {} escalated to M11 after {} retries",
                    deferred.getId(), deferred.getRetryCount());
        } else {
            deferred.setRetryAfter(now.plus(props.getDeferred().getRetryIntervalMinutes(), ChronoUnit.MINUTES));
            deferredRepository.save(deferred);
        }
    }

    private Set<UUID> citiesOnShift() {
        Set<UUID> cities = new HashSet<>();
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            if (live != null && live.getCityId() != null) {
                cities.add(live.getCityId());
            }
        }
        return cities;
    }
}
