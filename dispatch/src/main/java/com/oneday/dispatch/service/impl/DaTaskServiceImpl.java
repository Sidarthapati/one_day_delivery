package com.oneday.dispatch.service.impl;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.events.HubScanSeamProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
import com.oneday.dispatch.service.model.DaQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Implements the DA task-lifecycle transitions (design §6). Each op runs under the DA's lock, guards
 * ownership (404) and the legal status transition (409), persists the change, refreshes the in-memory
 * mirror, and emits the matching (gated) DA lifecycle event.
 */
@Service
class DaTaskServiceImpl implements DaTaskService {

    private static final Logger log = LoggerFactory.getLogger(DaTaskServiceImpl.class);

    private final DispatchQueueRepository queueRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final DaEventProducer daEventProducer;
    private final DispatchProperties props;
    private final HubScanSeamProducer hubScanSeamProducer;
    private final CityMeetingModePort meetingModePort;

    DaTaskServiceImpl(DispatchQueueRepository queueRepository,
                      DaCronAssignmentRepository cronRepository,
                      DaStatusService daStatusService,
                      DaEventProducer daEventProducer,
                      DispatchProperties props,
                      HubScanSeamProducer hubScanSeamProducer,
                      CityMeetingModePort meetingModePort) {
        this.queueRepository = queueRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.daEventProducer = daEventProducer;
        this.props = props;
        this.hubScanSeamProducer = hubScanSeamProducer;
        this.meetingModePort = meetingModePort;
    }

    private boolean isHubReturn(DispatchQueue task) {
        return meetingModePort.modeFor(task.getCityId()) == MeetingMode.HUB_RETURN;
    }

    @Override
    @Transactional
    public DaTaskView markEnRoute(UUID daId, UUID taskId) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.PICKUP);
            requireStatus(task, TaskStatus.QUEUED);
            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
            return save(task);
        });
    }

    @Override
    @Transactional
    public DaTaskView recordVanHandoff(UUID daId, UUID taskId, List<String> parcelScans, UUID vanId) {
        if (parcelScans == null || parcelScans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "At least one parcel scan is required");
        }
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId); 
            requireType(task, TaskType.PICKUP);
            requireStatus(task, TaskStatus.IN_PROGRESS);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            recordCronHandoff(daId, task.getOperatingDate(), parcelScans.size());
            // HUB_RETURN cities have no van — the DA hands off AT the hub, so emit the neutral
            // hub-return handoff event (not VAN_HANDOFF_COMPLETED, which would claim a van received it).
            // Both map to HANDED_TO_PICKUP_VAN in M4; only the event label differs.
            if (isHubReturn(task)) {
                daEventProducer.emitHubReturnHandoffCompleted(daId, task.getCityId(), task.getShipmentId());
                // M8-SEAM: the hub drop is the origin-hub inbound scan (advances to AT_ORIGIN_HUB → M7/M9).
                hubScanSeamProducer.emitHubOriginIn(task.getShipmentId());
            } else {
                daEventProducer.emitVanHandoffCompleted(daId, task.getCityId(), task.getShipmentId());
            }
            log.debug("Van handoff: task {} (van {}) completed with {} scan(s)", taskId, vanId, parcelScans.size());
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView markFailed(UUID daId, UUID taskId, String reason) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireActive(task);
            task.setStatus(TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            if (task.getTaskType() == TaskType.PICKUP) {
                daEventProducer.emitPickupFailed(daId, task.getCityId(), task.getShipmentId(), reason);
            } else {
                daEventProducer.emitDropFailed(daId, task.getCityId(), task.getShipmentId(), reason);
            }
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView markDropCollected(UUID daId, UUID taskId) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.DELIVERY);
            requireStatus(task, TaskStatus.QUEUED);
            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
            DaTaskView view = save(task);
            daEventProducer.emitDropCollected(daId, task.getCityId(), task.getShipmentId());
            // M8-SEAM: in HUB_RETURN cities the DA collects the delivery FROM the hub, so record the
            // hub-dest custody scan (ledger only — the DA's later DROP_* events drive the state).
            if (isHubReturn(task)) {
                hubScanSeamProducer.emitHubDestOut(task.getShipmentId());
            }
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView markDropCompleted(UUID daId, UUID taskId, boolean codCollected) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.DELIVERY);
            requireStatus(task, TaskStatus.IN_PROGRESS);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            daEventProducer.emitDropCompleted(daId, task.getCityId(), task.getShipmentId());
            if (codCollected) {
                daEventProducer.emitCodCollected(daId, task.getCityId(), task.getShipmentId());
            }
            return view;
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private DispatchQueue ownedTask(UUID daId, UUID taskId) {
        DispatchQueue task = queueRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task " + taskId));
        if (!task.getDaId().equals(daId)) {
            // Don't leak another DA's task — treat as not found for this DA.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task " + taskId + " for DA " + daId);
        }
        return task;
    }

    private DaTaskView save(DispatchQueue task) {
        queueRepository.save(task);
        QueueMirror.rebuild(daStatusService, queueRepository, task.getDaId(), task.getOperatingDate());
        return DaTaskView.of(task);
    }

    private void recordCronHandoff(UUID daId, LocalDate date, int parcelCount) {
        cronRepository.findByDaIdAndOperatingDate(daId, date).ifPresent(cron -> {
            cron.setHandoffCompletedAt(Instant.now());
            int prior = cron.getParcelCountHanded() != null ? cron.getParcelCountHanded() : 0;
            cron.setParcelCountHanded(prior + parcelCount);

            // HUB_RETURN crons carry no van and recur through the day (M6 gate off). A hub drop that
            // still has a later return today only COMPLETES this leg: roll the meeting to the next slot
            // and stay SCHEDULED so the hard constraint keeps gating, and free the DA to work until then.
            // The last return (no later slot) — and every van rendezvous (v1, single meeting) — is terminal.
            Instant next = (cron.getVanId() == null) ? nextSlotAfter(cron, cron.getScheduledMeetingTime()) : null;
            if (next != null) {
                cron.setScheduledMeetingTime(next);
                cron.setStatus(CronAssignmentStatus.SCHEDULED);
                cronRepository.save(cron);
                refreshMemCron(daId, next);
                if (daStatusService.getStatus(daId) == DaStatusEnum.AT_CRON) {
                    daStatusService.updateStatus(daId, DaStatusEnum.IDLE);
                }
            } else {
                cron.setStatus(CronAssignmentStatus.COMPLETED);
                cronRepository.save(cron);
            }
        });
    }

    /** First periodic meeting strictly after {@code reference} (the just-completed slot); null if none left. */
    private Instant nextSlotAfter(DaCronAssignment cron, Instant reference) {
        if (cron.getMeetingTimes() == null || cron.getMeetingTimes().isEmpty() || reference == null) {
            return null;
        }
        ZoneId zone = ZoneId.of(props.getShift().getZone());
        return cron.getMeetingTimes().stream()
                .map(LocalTime::parse)
                .map(t -> LocalDateTime.of(cron.getOperatingDate(), t).atZone(zone).toInstant())
                .filter(i -> i.isAfter(reference))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Keep the in-memory queue's cron in step so CronMonitorJob re-freezes ahead of the next return. */
    private void refreshMemCron(UUID daId, Instant nextMeeting) {
        DaQueue q = daStatusService.getQueue(daId);
        if (q != null && q.getCron() != null) {
            q.getCron().setScheduledMeetingTime(nextMeeting);
            q.getCron().setStatus(CronAssignmentStatus.SCHEDULED);
        }
    }

    private static void requireType(DispatchQueue task, TaskType expected) {
        if (task.getTaskType() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is a " + task.getTaskType() + ", not a " + expected);
        }
    }

    private static void requireStatus(DispatchQueue task, TaskStatus expected) {
        if (task.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is " + task.getStatus() + ", expected " + expected);
        }
    }

    private static void requireActive(DispatchQueue task) {
        if (task.getStatus() != TaskStatus.QUEUED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is " + task.getStatus() + " and cannot be failed");
        }
    }
}
