package com.oneday.airline.service.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * The single connection point to a real flight-booking vendor (§4 — "search for flights, book a
 * batch, check a flight's status, cancel a booking"). Which real airline/agency/booking system this
 * platform eventually connects to hasn't been decided, so {@link SimFlightProviderAdapter} sits
 * behind this port as a realistic stand-in; a real vendor adapter (e.g. AirLabs, or a GHA API) swaps
 * in later via {@code @Primary} with no change to anything that calls this port.
 */
public interface FlightProviderPort {

    /** Candidate flights on a lane for a date — the vendor's schedule/availability answer. */
    List<FlightCandidate> search(String originHub, String destHub, LocalDate date);

    /** Books a flight instance as one confirmed reservation; returns the vendor's confirmation. */
    BookingConfirmation book(String flightNo, LocalDate flightDate, String originHub, String destHub,
                             int weightGrams, int parcelCount);

    /** The vendor's current word on a booked flight — the trigger for M9's reassignment engine (§9). */
    FlightStatusResult status(String flightNo, LocalDate flightDate);

    record FlightCandidate(
            String flightNo,
            String carrier,
            LocalTime departureTime,
            LocalTime arrivalTime,
            int capacityKg) {
    }

    record BookingConfirmation(String providerRef) {
    }

    enum FlightRealWorldStatus {
        ON_TIME, DELAYED, CANCELLED, DEPARTED, LANDED
    }

    record FlightStatusResult(FlightRealWorldStatus status, Instant estimatedDeparture, Instant estimatedArrival) {
    }
}
