package com.oneday.grid.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.GridEventType;
import com.oneday.grid.events.payload.TileOverloadAlertEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class TileOverloadAlertProducer {

    private final EventPublisher eventPublisher;

    TileOverloadAlertProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void emit(UUID cityId, UUID tileId, UUID daId, LocalDate date,
                     String severity, double expectedOrders, int unservedOrders,
                     double adjustedScore, int sustainedMinutes) {
        TileOverloadAlertEvent event = new TileOverloadAlertEvent(
                GridEventType.TILE_OVERLOAD_ALERT, cityId, tileId, daId, date, severity,
                expectedOrders, unservedOrders, adjustedScore, sustainedMinutes,
                Instant.now()
        );
        eventPublisher.publish(EventStreams.GRID_EVENTS, event);
    }
}
