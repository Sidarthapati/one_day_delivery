package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;

import java.time.Instant;
import java.util.UUID;

/** Read model returned to the DA app after a task-lifecycle action. */
public record DaTaskView(
        UUID taskId,
        UUID shipmentId,
        TaskType taskType,
        TaskStatus status,
        int queuePosition,
        Instant expectedEta) {

    public static DaTaskView of(DispatchQueue row) {
        return new DaTaskView(row.getId(), row.getShipmentId(), row.getTaskType(),
                row.getStatus(), row.getQueuePosition(), row.getExpectedEta());
    }
}
