package com.oneday.exceptions.events;

import com.oneday.common.kafka.events.ReceiverRejectedEvent;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.service.ExceptionCaseService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReceiverRejectedExceptionConsumerTest {

    @Test
    void recordsRejectAsACustomerRejectedDeliveryAttempt() {
        ExceptionCaseService service = mock(ExceptionCaseService.class);
        ReceiverRejectedExceptionConsumer consumer = new ReceiverRejectedExceptionConsumer(service);

        UUID shipmentId = UUID.randomUUID();
        consumer.onReceiverRejected(new ReceiverRejectedEvent(
                shipmentId, "1DD-BLR-20260828-00001", UUID.randomUUID(), "1DD-ORD",
                "SHIFT_2", 12.97, 77.61, UUID.randomUUID()));

        // Not DA-attributable, DELIVERY_FAILED type, CUSTOMER_REJECTED reason → counts toward the cap.
        verify(service).captureDaFailure(shipmentId, "1DD-BLR-20260828-00001",
                ExceptionType.DELIVERY_FAILED, ExceptionReason.CUSTOMER_REJECTED, false);
    }
}
