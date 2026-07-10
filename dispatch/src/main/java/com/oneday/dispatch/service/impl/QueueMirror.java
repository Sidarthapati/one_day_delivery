package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaQueue;
import com.oneday.dispatch.service.model.DispatchTask;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Keeps a DA's in-memory {@link DaQueue} in step with the persisted active rows. Both the assignment
 * engine and the task-lifecycle service mutate {@code dispatch_queue} then call this to refresh the
 * mirror that the station view / queue-depth flush read. Call under the DA's lock.
 */
final class QueueMirror {

    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS);

    private QueueMirror() {}

    static void rebuild(DaStatusService daStatusService, DispatchQueueRepository queueRepository,
                        UUID daId, LocalDate date) {
        DaQueue mem = daStatusService.getQueue(daId);
        if (mem == null) {
            return;
        }
        List<DispatchQueue> active = queueRepository
                .findByDaIdAndOperatingDateAndStatusIn(daId, date, ACTIVE).stream()
                .sorted(Comparator.comparingInt(DispatchQueue::getQueuePosition))
                .toList();
        mem.getTasks().clear();
        for (DispatchQueue r : active) {
            mem.getTasks().add(new DispatchTask(daId, r.getShipmentId(), r.getTaskType(),
                    r.getTaskLat(), r.getTaskLon(), r.getQueuePosition(), r.getStatus(), r.getExpectedEta()));
        }
    }
}
