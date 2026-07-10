package com.oneday.common.kafka.events.flight;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.FlightEventType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * M9 → M7 on {@code oneday.flight.events}: a flight's departure/cutoff moved (delay or prepone)
 * on the <b>same</b> flight (§10). Advisory — M7 only adjusts the bag's seal window; it moves no
 * parcels. If a prepone strands parcels, M9 emits a separate {@code FlightReassignedEvent} for them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightTimeChangedEvent(
        String flightNo,
        LocalDate flightDate,
        String destHub,
        Instant newDeparture,
        Instant newCutoff) implements DomainEvent {

    @Override
    public String partitionKey() {
        return flightNo;
    }

    @Override
    public String eventTypeName() {
        return FlightEventType.FLIGHT_TIME_CHANGED.name();
    }
}
