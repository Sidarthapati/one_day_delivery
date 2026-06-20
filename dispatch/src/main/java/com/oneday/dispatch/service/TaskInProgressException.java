package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.TaskType;

import java.util.UUID;

/**
 * Thrown when a cancel targets a task already IN_PROGRESS — the DA is mid-pickup/drop, so M5 cannot
 * silently drop it; M11 handles it operationally (design §6, cancel flow).
 */
public class TaskInProgressException extends RuntimeException {

    public TaskInProgressException(UUID shipmentId, TaskType taskType) {
        super("Cannot cancel " + taskType + " for shipment " + shipmentId + ": task is IN_PROGRESS");
    }
}
