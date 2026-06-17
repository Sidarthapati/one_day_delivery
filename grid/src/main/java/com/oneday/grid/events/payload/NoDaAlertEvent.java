package com.oneday.grid.events.payload;

import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.GridEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by M3 on the consolidated exchange oneday.grid.events (eventType = NO_DA_ALERT)
 * when no DA is assigned to an active tile for today.
 * Consumed by M5 (Dispatch) and M11 (Exceptions / call-centre queue).
 */
public record NoDaAlertEvent(
        GridEventType eventType,
        UUID cityId,
        UUID tileId,
        LocalDate validDate,
        String reason,
        Instant alertedAt
) implements DomainEvent {

    @Override public String partitionKey()  { return tileId.toString(); }
    @Override public String eventTypeName() { return eventType.name(); }
}
