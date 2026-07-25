package com.oneday.sla.events;

import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.sla.service.SlaLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * M5 DA events. Parcel-scoped signals (pickup completed, cron missed) trigger a re-evaluation on the
 * fresh information; DA-scoped signals (DA_ABSENT — no shipment) are logged. Takes the one rich type
 * M5 produces on {@code oneday.da.events}.
 */
@Component
public class SlaDaEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaDaEventsConsumer.class);

    private final SlaLifecycleService lifecycle;

    public SlaDaEventsConsumer(SlaLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = SlaMessagingTopology.DA_QUEUE)
    public void onDaEvent(DaLifecycleEvent event) {
        if (event.eventType() == null) {
            return;
        }
        switch (event.eventType()) {
            case PICKUP_COMPLETED, CRON_MISSED -> lifecycle.touch(event.shipmentId());
            case DA_ABSENT -> log.debug("DA_ABSENT for da {} — city-level risk, not attributed", event.daId());
            default -> { /* other DA events don't affect SLA accounting */ }
        }
    }
}
