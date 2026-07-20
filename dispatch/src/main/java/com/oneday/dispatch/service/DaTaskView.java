package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model returned to the DA app — the driver's task, including the stop's
 * coordinates ({@code taskLat}/{@code taskLon}, from {@code dispatch_queue}) so the
 * app can offer a free "Open in Maps" deeplink (geo:) with no maps-API cost.
 */
public record DaTaskView(
        UUID taskId,
        UUID shipmentId,
        String shipmentRef,
        TaskType taskType,
        TaskStatus status,
        int queuePosition,
        Instant expectedEta,
        double taskLat,
        double taskLon,
        String paymentMode) {

    /** Mutation responses don't need the ref (the app already holds it from listTasks). */
    public static DaTaskView of(DispatchQueue row) {
        return of(row, null);
    }

    public static DaTaskView of(DispatchQueue row, String shipmentRef) {
        return new DaTaskView(row.getId(), row.getShipmentId(), shipmentRef, row.getTaskType(),
                row.getStatus(), row.getQueuePosition(), row.getExpectedEta(),
                row.getTaskLat(), row.getTaskLon(), row.getPaymentMode());
    }
}
