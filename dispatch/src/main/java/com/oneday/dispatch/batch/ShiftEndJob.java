package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * At shift end: for the shift that is ending, any task still {@code QUEUED} (never started) is deferred
 * to M11, that shift's DAs are set {@code OFFLINE}, the final state is flushed, and only those DAs are
 * dropped from memory. Crucially it does NOT tear down the other shift's roster — the SHIFT_2 roster
 * loaded at 13:45 must survive the 14:05 SHIFT_1 end. {@code IN_PROGRESS}/{@code COMPLETED} tasks are
 * left alone. Idempotent — a second run finds nothing loaded for that shift.
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

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Scheduled(cron = "${dispatch.shift.end-cron:0 5 14,22 * * *}", zone = "${dispatch.shift.zone:Asia/Kolkata}")
    public void onSchedule() {
        endShift(LocalDate.now(IST), currentEndingShift());
    }

    /** Which shift the current fire time is ending (before 18:00 = SHIFT_1, else SHIFT_2). */
    static Shift currentEndingShift() {
        return LocalTime.now(IST).getHour() < 18 ? Shift.SHIFT_1 : Shift.SHIFT_2;
    }

    /** Package-visible for direct testing. Ends only {@code shift}'s DAs. */
    @Transactional
    public void endShift(LocalDate date, Shift shift) {
        // SHIFT_2 is the day's last shift — its still-QUEUED tasks roll to tomorrow's SHIFT_1, so their
        // deferred rows carry tomorrow's operating_date. SHIFT_1 leftovers stay on today (same-day SHIFT_2
        // covers them via the retry engine).
        LocalDate rolloverDate = shift == Shift.SHIFT_2 ? date.plusDays(1) : date;
        List<UUID> ended = new ArrayList<>();
        int rolled = 0;
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            if (live == null) {
                continue;
            }
            // Only tear down the shift that is ending; leave the other shift's roster running.
            if (!shift.name().equals(live.getShiftType())) {
                continue;
            }
            UUID cityId = live.getCityId();

            List<DispatchQueue> queued = queueRepository
                    .findByDaIdAndOperatingDateAndStatusIn(daId, date, List.of(TaskStatus.QUEUED));
            for (DispatchQueue task : queued) {
                task.setStatus(TaskStatus.DEFERRED);
                deferredRepository.save(toDeferred(task, rolloverDate));
                eventProducer.emitTaskDeferredShiftEnded(daId, cityId, task.getShipmentId());
            }
            if (!queued.isEmpty()) {
                queueRepository.saveAll(queued);
                rolled += queued.size();
                log.info("Shift end {}: rolled {} QUEUED tasks for DA {} to {}",
                        shift, queued.size(), daId, rolloverDate);
            }
            daStatusService.updateStatus(daId, DaStatusEnum.OFFLINE);
            ended.add(daId);
        }
        if (rolled > 0) {
            log.info("Shift end {} on {}: {} tasks carried over to {}", shift, date, rolled, rolloverDate);
        }

        // Persist the final state immediately, then drop only the ended shift's DAs from memory.
        daStatusService.flushDirtyStatuses();
        ended.forEach(daStatusService::clear);
    }

    private static DeferredDispatch toDeferred(DispatchQueue task, LocalDate operatingDate) {
        DeferredDispatch d = new DeferredDispatch();
        d.setCityId(task.getCityId());
        d.setShipmentId(task.getShipmentId());
        d.setTaskType(task.getTaskType());
        d.setTileId(task.getTileId());
        d.setTaskLat(task.getTaskLat());
        d.setTaskLon(task.getTaskLon());
        d.setDeferReason(DeferReason.SHIFT_ENDED);
        d.setOperatingDate(operatingDate);
        // deferredAt + status=PENDING default in @PrePersist
        return d;
    }
}
