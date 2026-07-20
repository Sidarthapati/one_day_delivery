package com.oneday.sla.events;

import com.oneday.common.kafka.events.flight.FlightReassignedEvent;
import com.oneday.common.kafka.events.flight.FlightTimeChangedEvent;
import com.oneday.sla.service.SlaLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * M9 flight events. {@code FLIGHT_REASSIGNED} carries the affected parcel ids + the new cutoff, so
 * M10 re-baselines those parcels' airport leg; {@code FLIGHT_TIME_CHANGED} is flight-level (no parcel
 * list) → logged. Dormant (autoStartup=false) until M9 is built, mirroring M7's flight consumer.
 */
@Component
@RabbitListener(queues = SlaMessagingTopology.FLIGHT_QUEUE, autoStartup = "false")
public class SlaFlightEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlaFlightEventsConsumer.class);

    private final SlaLifecycleService lifecycle;

    public SlaFlightEventsConsumer(SlaLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @RabbitHandler
    public void onReassigned(FlightReassignedEvent event) {
        // parcelId == shipmentId in v1 (no M8 barcode yet).
        lifecycle.enrichFlightCutoff(event.parcelIds(), event.newCutoff());
    }

    @RabbitHandler
    public void onTimeChanged(FlightTimeChangedEvent event) {
        log.debug("FLIGHT_TIME_CHANGED for {} — no parcel list to attribute in v1", event.flightNo());
    }

    @RabbitHandler(isDefault = true)
    public void onOther(Object event) {
        log.trace("Ignoring flight event {}", event.getClass().getSimpleName());
    }
}
