package com.oneday.airline.service.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The connection point to the freight consolidator's flight data (§4 — "search for flights, check a
 * flight's status"). Access is read-only direct-DB, not an API — there is no vendor to "book" a
 * reservation with from here; {@link com.oneday.airline.consolidator.ConsolidatorFlightProviderAdapter}
 * reads the consolidator's (mocked) production schema. A booking is recorded purely on our own side
 * ({@code Awb}); actually reserving capacity with the consolidator happens out-of-band.
 */
public interface FlightProviderPort {

    /** Candidate flights on a lane for a date — the consolidator's schedule/availability answer. */
    List<FlightCandidate> search(String originHub, String destHub, LocalDate date);

    /** The consolidator's current word on a booked flight — the trigger for M9's reassignment engine (§9). */
    FlightStatusResult status(String flightNo, LocalDate flightDate);

    record FlightCandidate(
            String flightNo,
            String carrier,
            LocalTime departureTime,
            LocalTime arrivalTime,
            int capacityKg) {
    }

    enum FlightRealWorldStatus {
        ON_TIME, DELAYED, CANCELLED, DEPARTED, LANDED
    }

    record FlightStatusResult(FlightRealWorldStatus status, Instant estimatedDeparture, Instant estimatedArrival) {
    }
}
