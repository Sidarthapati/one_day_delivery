package com.oneday.common.kafka.events.flight;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.FlightEventType;
import com.oneday.common.kafka.enums.FlightReassignReason;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * M9 → M7 on {@code oneday.flight.events}: (re)assign parcels to a flight (M7-D-006). The sole
 * reschedule trigger M7 executes. Keyed on <b>flight number</b>, never a hub bag id — the flight is
 * the bag's external identity and M7 resolves flight→bag(s) itself.
 *
 * <ul>
 *   <li>{@code parcelIds} present → move exactly those parcels;</li>
 *   <li>{@code parcelIds} null/empty → move the whole open bag for {@code fromFlightNo}.</li>
 * </ul>
 *
 * M9 is unbuilt; this is the contract it will produce (and a test producer exercises now).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightReassignedEvent(
        String toFlightNo,
        LocalDate toFlightDate,
        String destHub,
        Instant newCutoff,
        String fromFlightNo,
        List<UUID> parcelIds,
        FlightReassignReason reason) implements DomainEvent {

    @Override
    public String partitionKey() {
        return toFlightNo;
    }

    @Override
    public String eventTypeName() {
        return FlightEventType.FLIGHT_REASSIGNED.name();
    }
}
