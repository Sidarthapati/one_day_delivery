package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.dto.response.DispatchExecutionStats;
import com.oneday.dispatch.dto.response.DispatchExecutionStats.DaPace;
import com.oneday.dispatch.repository.DaPaceRow;
import com.oneday.dispatch.repository.DeliveryOutcome;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DispatchMetricsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
class DispatchMetricsServiceImpl implements DispatchMetricsService {

    private final DispatchQueueRepository queueRepository;

    DispatchMetricsServiceImpl(DispatchQueueRepository queueRepository) {
        this.queueRepository = queueRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public DispatchExecutionStats execution(LocalDate date, UUID scopeCityId) {
        DeliveryOutcome outcome = queueRepository.deliveryOutcome(scopeCityId, date);
        long completed = outcome != null ? outcome.getCompleted() : 0;
        long failed = outcome != null ? outcome.getFailed() : 0;
        long attempts = completed + failed;
        Double successPct = attempts == 0 ? null : (double) completed / attempts;

        Instant now = Instant.now();
        List<DaPace> das = queueRepository.paceByDa(scopeCityId, date).stream()
                .map(r -> toPace(r, now))
                .sorted(Comparator.comparingLong(DaPace::stopsPending).reversed()
                        .thenComparing(Comparator.comparingLong(DaPace::stopsDone).reversed()))
                .toList();

        return new DispatchExecutionStats(date, successPct, completed, failed, das);
    }

    private static DaPace toPace(DaPaceRow r, Instant now) {
        return new DaPace(r.getDaId(), r.getDone(), r.getLastHour(), r.getPending(),
                avgPerHour(r.getDone(), r.getFirstAssigned(), now));
    }

    /** Stops done over hours on shift (since first assignment). Guards a fresh DA: &lt;6 min elapsed → 0,
     *  so one early stop doesn't read as an implausible 60/hr. */
    private static double avgPerHour(long done, Instant firstAssigned, Instant now) {
        if (done == 0 || firstAssigned == null) {
            return 0.0;
        }
        double hours = Duration.between(firstAssigned, now).toSeconds() / 3600.0;
        if (hours < 0.1) {
            return 0.0;
        }
        return done / hours;
    }
}
