package com.oneday.exceptions.events;

import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.service.ExceptionCaseService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M5's {@code oneday.da.events} — the authoritative failure signal, carrying the free-text
 * {@code reasonCode} M4 drops. A DROP/PICKUP failure opens (or bumps the attempt on) a case; a cron miss
 * opens one too when it's shipment-scoped. This is the single opener/attempt-counter, so one failure =
 * one attempt (no double-count against the shipment-event stream).
 */
@Component
public class ExceptionDaEventsConsumer {

    private final ExceptionCaseService service;

    public ExceptionDaEventsConsumer(ExceptionCaseService service) {
        this.service = service;
    }

    @RabbitListener(queues = ExceptionMessagingTopology.DA_QUEUE)
    public void onDaEvent(DaLifecycleEvent event) {
        if (event.eventType() == null) {
            return;
        }
        ExceptionReason reason = ExceptionReason.fromCode(event.reasonCode());
        switch (event.eventType()) {
            case DROP_FAILED -> capture(event, ExceptionType.DELIVERY_FAILED, reason);
            case PICKUP_FAILED -> capture(event, ExceptionType.PICKUP_FAILED, reason);
            case CRON_MISSED -> capture(event, ExceptionType.CRON_MISSED, ExceptionReason.CRON_MISSED);
            default -> { /* not a failure signal */ }
        }
    }

    private void capture(DaLifecycleEvent event, ExceptionType type, ExceptionReason reason) {
        // DA-attributable when the DA is the cause: a no-show, or a missed cron.
        boolean daAttributable = reason == ExceptionReason.DA_NO_SHOW || type == ExceptionType.CRON_MISSED;
        service.captureDaFailure(event.shipmentId(), event.shipmentRef(), type, reason, daAttributable);
    }
}
