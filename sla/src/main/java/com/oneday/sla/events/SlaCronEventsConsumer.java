package com.oneday.sla.events;

import com.oneday.common.kafka.events.cron.CronEventPayload;
import com.oneday.common.kafka.events.cron.LoopOverflowEvent;
import com.oneday.sla.service.SlaLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * M6 routing events. {@code LOOP_OVERFLOW} is parcel-keyed and tightens the open leg's deadline;
 * van-level signals (VAN_RUNNING_LATE, VAN_BREAKDOWN) carry no parcel id, so in v1 they are logged,
 * not attributed (M10-D-007). Takes the sealed base.
 */
@Component
public class SlaCronEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaCronEventsConsumer.class);

    private final SlaLifecycleService lifecycle;

    public SlaCronEventsConsumer(SlaLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = SlaMessagingTopology.CRON_QUEUE)
    public void onCronEvent(CronEventPayload event) {
        if (event instanceof LoopOverflowEvent l) {
            lifecycle.enrichLoopOverflow(l.parcelId(), l.deadline());
        } else {
            log.trace("Ignoring cron event {}", event.eventTypeName());
        }
    }
}
