package com.oneday.exceptions.dto;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.exceptions.domain.Disposition;
import com.oneday.exceptions.domain.ExceptionCase;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionStatus;
import com.oneday.exceptions.domain.ExceptionType;

import java.time.Instant;
import java.util.UUID;

/** One row of the problem-solve queue. */
public record ExceptionCaseSummary(
        UUID id,
        UUID shipmentId,
        String shipmentRef,
        // Parent order back-ref (null for legacy/pre-order cases) — lets the queue chip a parcel to its order.
        String orderRef,
        String originCity,
        String destCity,
        DeliveryType deliveryType,
        ExceptionType type,
        ExceptionReason reasonCode,
        ExceptionStatus status,
        Disposition disposition,
        int attemptNo,
        boolean daAttributable,
        String assignedTo,
        Instant openedAt) {

    public static ExceptionCaseSummary from(ExceptionCase c) {
        return new ExceptionCaseSummary(
                c.getId(), c.getShipmentId(), c.getShipmentRef(), c.getOrderRef(),
                c.getOriginCity(), c.getDestCity(),
                c.getDeliveryType(), c.getType(), c.getReasonCode(), c.getStatus(), c.getDisposition(),
                c.getAttemptNo(), c.isDaAttributable(), c.getAssignedTo(), c.getOpenedAt());
    }
}
