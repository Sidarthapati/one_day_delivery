package com.oneday.sla.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.sla.SlaBreachedEvent;
import com.oneday.common.kafka.events.sla.SlaEscalationRaisedEvent;
import org.springframework.stereotype.Component;

/** Publishes M10's outbound events on {@code oneday.sla.events} (→ M11, ops/notification service). */
@Component
public class SlaEventProducer {

    private final EventPublisher eventPublisher;

    public SlaEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void escalationRaised(SlaEscalationRaisedEvent event) {
        eventPublisher.publish(EventStreams.SLA_EVENTS, event);
    }

    public void breached(SlaBreachedEvent event) {
        eventPublisher.publish(EventStreams.SLA_EVENTS, event);
    }
}
