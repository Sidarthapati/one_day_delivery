package com.oneday.exceptions.events;

import com.oneday.common.kafka.events.BaseShipmentEvent;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import com.oneday.exceptions.service.ExceptionCaseService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M4's {@code oneday.shipments.events} to advance a case's lifecycle: RTO progression and a
 * successful terminal delivery (which auto-resolves a lingering case). Pickup/delivery <em>failures</em>
 * are opened via the DA path ({@link ExceptionDaEventsConsumer}), which carries the reason M4 drops.
 */
@Component
public class ExceptionShipmentEventsConsumer {

    private final ExceptionCaseService service;

    public ExceptionShipmentEventsConsumer(ExceptionCaseService service) {
        this.service = service;
    }

    @RabbitListener(queues = ExceptionMessagingTopology.SHIPMENTS_QUEUE)
    public void onShipmentEvent(BaseShipmentEvent event) {
        if (event instanceof ShipmentStateChangedEvent changed && changed.getToState() != null) {
            service.onShipmentStateChanged(changed.getShipmentId(), changed.getToState());
        }
    }
}
