package com.oneday.dispatch.service;

import com.oneday.common.port.ShipmentContactPort.ShipmentContact;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model returned to the DA app — the driver's task, including the stop's
 * coordinates ({@code taskLat}/{@code taskLon}, from {@code dispatch_queue}) so the
 * app can offer a free "Open in Maps" deeplink (geo:) with no maps-API cost.
 *
 * <p>{@code contactName}/{@code contactPhone}/{@code addressText}/{@code codAmountPaise} are the
 * on-the-ground fields (Call button + address block + "Collect ₹X"), populated by {@code listTasks}
 * from {@link ShipmentContact} — the sender end for a PICKUP, the receiver end for a DELIVERY. They
 * are null on mutation responses (the app already holds them from listTasks), like {@code shipmentRef}.
 */
public record DaTaskView(
        UUID taskId,
        UUID shipmentId,
        String shipmentRef,
        UUID orderId,
        String orderRef,
        TaskType taskType,
        TaskStatus status,
        int queuePosition,
        Instant expectedEta,
        double taskLat,
        double taskLon,
        String paymentMode,
        String contactName,
        String contactPhone,
        String addressText,
        Long codAmountPaise,
        boolean pickedUp) {

    /** Mutation responses don't need the ref/contact (the app already holds them from listTasks). */
    public static DaTaskView of(DispatchQueue row) {
        return of(row, null);
    }

    public static DaTaskView of(DispatchQueue row, String shipmentRef) {
        return new DaTaskView(row.getId(), row.getShipmentId(), shipmentRef,
                row.getOrderId(), row.getOrderRef(), row.getTaskType(),
                row.getStatus(), row.getQueuePosition(), row.getExpectedEta(),
                row.getTaskLat(), row.getTaskLon(), row.getPaymentMode(),
                null, null, null, null, row.isPickedUp());
    }

    /** List rows carry the field contact — sender end for a PICKUP, receiver end for a DELIVERY. */
    public static DaTaskView of(DispatchQueue row, String shipmentRef, ShipmentContact c) {
        boolean pickup = row.getTaskType() == TaskType.PICKUP;
        String name = c == null ? null : pickup ? c.senderName() : c.receiverName();
        String phone = c == null ? null : pickup ? c.senderPhone() : c.receiverPhone();
        String addr = c == null ? null : pickup ? c.originAddress() : c.destAddress();
        Long cod = c == null ? null : c.codAmountPaise();
        return new DaTaskView(row.getId(), row.getShipmentId(), shipmentRef,
                row.getOrderId(), row.getOrderRef(), row.getTaskType(),
                row.getStatus(), row.getQueuePosition(), row.getExpectedEta(),
                row.getTaskLat(), row.getTaskLon(), row.getPaymentMode(),
                name, phone, addr, cod, row.isPickedUp());
    }
}
