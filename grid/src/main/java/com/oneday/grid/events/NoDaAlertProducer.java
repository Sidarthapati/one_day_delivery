package com.oneday.grid.events;

import com.oneday.common.messaging.EventPublisher;
import com.oneday.common.messaging.EventStreams;
import com.oneday.common.messaging.enums.GridEventType;
import com.oneday.grid.events.payload.NoDaAlertEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class NoDaAlertProducer {

    private final EventPublisher eventPublisher;

    NoDaAlertProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void emit(UUID cityId, UUID tileId, LocalDate validDate, String reason) {
        NoDaAlertEvent event = new NoDaAlertEvent(
                GridEventType.NO_DA_ALERT, cityId, tileId, validDate, reason, Instant.now());
        eventPublisher.publish(EventStreams.GRID_EVENTS, event);
    }
}
