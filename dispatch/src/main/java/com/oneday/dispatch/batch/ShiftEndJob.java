package com.oneday.dispatch.batch;

import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.domain.DeferredDispatch;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DeferredDispatchRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * At shift end: any task still {@code QUEUED} (never started) is deferred to M11, every DA is set
 * {@code OFFLINE}, the final state is flushed, and the in-memory maps are cleared. {@code IN_PROGRESS}
 * and {@code COMPLETED} tasks are left alone. Idempotent — a second run finds nothing loaded.
 */
@Component
public class ShiftEndJob {

    private static final Logger log = LoggerFactory.getLogger(ShiftEndJob.class);

    private final DaStatusService daStatusService;
    private final DispatchQueueRepository queueRepository;
    private final DeferredDispatchRepository deferredRepository;
    private final DaEventProducer eventProducer;

    public ShiftEndJob(DaStatusService daStatusService,
                       DispatchQueueRepository queueRepository,
                       DeferredDispatchRepository deferredRepository,
                       DaEventProducer eventProducer) {
        this.daStatusService = daStatusService;
        this.queueRepository = queueRepository;
        this.deferredRepository = deferredRepository;
        this.eventProducer = eventProducer;
    }

    @Scheduled(cron = "${dispatch.shift.end-cron:0 5 14,22 * * *}", zone = "${dispatch.shift.zone:Asia/Kolkata}")
    public void onSchedule() {
        endShift(LocalDate.now());
    }

    /** Package-visible for direct testing. */
    @Transactional
    public void endShift(LocalDate date) {
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            UUID cityId = live != null ? live.getCityId() : null;

            List<DispatchQueue> queued = queueRepository
                    .findByDaIdAndOperatingDateAndStatusIn(daId, date, List.of(TaskStatus.QUEUED));
            for (DispatchQueue task : queued) {
                task.setStatus(TaskStatus.DEFERRED);
                deferredRepository.save(toDeferred(task));
                eventProducer.emitTaskDeferredShiftEnded(daId, cityId, task.getShipmentId());
            }
            if (!queued.isEmpty()) {
                queueRepository.saveAll(queued);
                log.info("Shift end: deferred {} QUEUED tasks for DA {}", queued.size(), daId);
            }
            daStatusService.updateStatus(daId, DaStatusEnum.OFFLINE);
        }

        // Persist the final state immediately, then drop everything from memory.
        daStatusService.flushDirtyStatuses();
        daStatusService.clearAll();
    }

    private static DeferredDispatch toDeferred(DispatchQueue task) {
        DeferredDispatch d = new DeferredDispatch();
        d.setCityId(task.getCityId());
        d.setShipmentId(task.getShipmentId());
        d.setTaskType(task.getTaskType());
        d.setTileId(task.getTileId());
        d.setTaskLat(task.getTaskLat());
        d.setTaskLon(task.getTaskLon());
        d.setDeferReason(DeferReason.SHIFT_ENDED);
        d.setOperatingDate(task.getOperatingDate());
        // deferredAt + status=PENDING default in @PrePersist
        return d;
    }
}
