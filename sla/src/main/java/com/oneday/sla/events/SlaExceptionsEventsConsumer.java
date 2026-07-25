package com.oneday.sla.events;

import com.oneday.common.kafka.events.ExceptionsEvent;
import com.oneday.sla.service.SlaLifecycleService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * M11 exception events. The shipment's own state changes (RTO_INITIATED/COMPLETED, reschedules) drive
 * the SLA via the shipments backbone; this consumer just re-evaluates on the same signal so a failure
 * reflects promptly even if the state event is delayed. Dormant in practice until M11 produces.
 */
@Component
public class SlaExceptionsEventsConsumer {

    private final SlaLifecycleService lifecycle;

    public SlaExceptionsEventsConsumer(SlaLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = SlaMessagingTopology.EXCEPTIONS_QUEUE)
    public void onExceptionEvent(ExceptionsEvent event) {
        lifecycle.touch(event.shipmentId());
    }
}
