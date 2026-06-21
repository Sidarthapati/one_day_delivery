package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
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

    DaTaskServiceImpl(DispatchQueueRepository queueRepository,
                      DaCronAssignmentRepository cronRepository,
                      DaStatusService daStatusService,
                      DaEventProducer daEventProducer) {
        this.queueRepository = queueRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.daEventProducer = daEventProducer;
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
            daEventProducer.emitVanHandoffCompleted(daId, task.getCityId(), task.getShipmentId());
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
            cron.setStatus(CronAssignmentStatus.COMPLETED);
            cron.setHandoffCompletedAt(Instant.now());
            int prior = cron.getParcelCountHanded() != null ? cron.getParcelCountHanded() : 0;
            cron.setParcelCountHanded(prior + parcelCount);
            cronRepository.save(cron);
        });
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
