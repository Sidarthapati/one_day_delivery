package com.oneday.exceptions.events;

import com.oneday.common.kafka.events.ReceiverRejectedEvent;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.service.ExceptionCaseService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M4's {@code RECEIVER_REJECTED} (a receiver declined today's delivery via the accept/reject
 * prompt) and records it as a delivery attempt with reason {@code CUSTOMER_REJECTED} — so a proactive
 * reject counts toward the same reattempt cap as a door failure and rolls into an ops-confirmed RTO at
 * the cap. Not DA-attributable (the customer chose it). M5's {@code ReceiverRejectedConsumer} handles
 * the physical re-park on the same event; this consumer only owns the attempt accounting.
 */
@Component
public class ReceiverRejectedExceptionConsumer {

    private final ExceptionCaseService service;

    public ReceiverRejectedExceptionConsumer(ExceptionCaseService service) {
        this.service = service;
    }

    @RabbitListener(queues = ExceptionMessagingTopology.DELIVERY_CONFIRMATIONS_QUEUE)
    public void onReceiverRejected(ReceiverRejectedEvent event) {
        service.captureDaFailure(event.shipmentId(), event.shipmentRef(),
                ExceptionType.DELIVERY_FAILED, ExceptionReason.CUSTOMER_REJECTED, false);
    }
}
