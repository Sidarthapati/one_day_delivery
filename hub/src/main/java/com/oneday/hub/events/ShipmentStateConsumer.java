package com.oneday.hub.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.events.ShipmentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M4 shipment state changes from {@code oneday.shipments.events} and treats AT_ORIGIN_HUB
 * (van/self-drop) — and, in PR #2, AT_DEST_HUB — as the sort trigger (§5, M7-D-005).
 *
 * <p>Dormant (autoStartup=false): the queue is declared so the subscription survives, but until M4
 * actually produces these events — and the event carries (or M7 can resolve) the hub UUID needed to
 * call {@code HubReceivingService} — sortation is driven via the REST dock endpoints and in tests.
 * Wiring the trigger to the service finalizes with the M4 producer contract.</p>
 */
@Component
public class ShipmentStateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentStateConsumer.class);

    @RabbitListener(queues = HubMessagingTopology.SHIPMENTS_QUEUE, autoStartup = "false")
    public void onShipmentStateChanged(ShipmentStateChangedEvent event) {
        if (event.getToState() == ShipmentState.AT_ORIGIN_HUB) {
            // TODO(M4 producer): resolve hub UUID for this parcel and call HubReceivingService.
            log.debug("AT_ORIGIN_HUB sort trigger for shipmentRef={} — REST/test-driven in PR #1",
                    event.getShipmentRef());
        }
    }
}
