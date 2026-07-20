package com.oneday.sla.events;

import com.oneday.common.kafka.events.BaseShipmentEvent;
import com.oneday.common.kafka.events.ShipmentCancelledEvent;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.sla.service.SlaLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The SLA backbone: M4's {@code oneday.shipments.events}. CREATED opens the SLA + leg plan;
 * STATE_CHANGED advances/closes legs; CANCELLED closes it. Takes the abstract base so the header's
 * concrete type deserializes; dispatches by instance.
 */
@Component
public class SlaShipmentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaShipmentEventsConsumer.class);

    private final SlaLifecycleService lifecycle;

    public SlaShipmentEventsConsumer(SlaLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitListener(queues = SlaMessagingTopology.SHIPMENTS_QUEUE)
    public void onShipmentEvent(BaseShipmentEvent event) {
        if (event instanceof ShipmentCreatedEvent created) {
            lifecycle.onCreated(created);
        } else if (event instanceof ShipmentStateChangedEvent changed) {
            lifecycle.onStateChanged(changed);
        } else if (event instanceof ShipmentCancelledEvent cancelled) {
            lifecycle.onCancelled(cancelled);
        } else {
            log.trace("Ignoring shipment event {}", event.eventTypeName());
        }
    }
}
