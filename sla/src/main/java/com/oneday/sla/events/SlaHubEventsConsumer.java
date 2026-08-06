package com.oneday.sla.events;

import com.oneday.common.kafka.events.hub.DestSortCompleteEvent;
import com.oneday.common.kafka.events.hub.HubEventPayload;
import com.oneday.common.kafka.events.hub.ParcelSortedForDeliveryEvent;
import com.oneday.sla.service.SlaLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * M7 hub events. The parcel-keyed ones sharpen the dest-hub / last-mile legs; the rest are logged
 * (bag/overload signals aren't attributable to a single parcel in v1). Takes the sealed base.
 */
@Component
public class SlaHubEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaHubEventsConsumer.class);

    private final SlaLifecycleService lifecycle;

    public SlaHubEventsConsumer(SlaLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = SlaMessagingTopology.HUB_QUEUE)
    public void onHubEvent(HubEventPayload event) {
        if (event instanceof ParcelSortedForDeliveryEvent p) {
            lifecycle.enrichLastMileDeadline(p.parcelId(), p.slaDeadline());
        } else if (event instanceof DestSortCompleteEvent d) {
            lifecycle.touch(d.parcelId());
        } else {
            log.trace("Ignoring hub event {}", event.eventTypeName());
        }
    }
}
