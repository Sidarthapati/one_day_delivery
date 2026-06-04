package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.CronEvent;
import com.oneday.orders.service.ShipmentStateMachine;
import com.oneday.orders.service.TransitionContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M6 cron (van-leg departure) events from {@code oneday.cron.events} and drives the
 * M4 state machine. Dormant by default ({@code autoStartup=false}) until M6 produces on this topic.
 */
@Component
public class CronEventsConsumer {

    private static final String SOURCE = "m6-cron-consumer";

    private final ShipmentStateMachine stateMachine;

    CronEventsConsumer(ShipmentStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @KafkaListener(topics = KafkaTopics.CRON_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onCronEvent(CronEvent event) {
        ShipmentState target = switch (event.eventType()) {
            case DEPARTED_HUB     -> ShipmentState.DISPATCHED_TO_AIRPORT;
            case DEPARTED_AIRPORT -> ShipmentState.DISPATCHED_TO_HUB;
        };
        stateMachine.transition(event.shipmentId(), target,
                TransitionContext.fromKafka(SOURCE, String.valueOf(event.shipmentId())));
    }
}
