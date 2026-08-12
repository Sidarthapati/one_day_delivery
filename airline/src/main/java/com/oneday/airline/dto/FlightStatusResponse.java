package com.oneday.airline.dto;

import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.service.provider.FlightProviderPort;

import java.time.Instant;
import java.time.LocalDate;

public record FlightStatusResponse(
        String flightNo,
        LocalDate flightDate,
        String status,
        Instant estimatedDeparture,
        Instant estimatedArrival) {

    public static FlightStatusResponse from(String flightNo, LocalDate flightDate,
                                             FlightProviderPort.FlightStatusResult result) {
        return new FlightStatusResponse(flightNo, flightDate, result.status().name(),
                result.estimatedDeparture(), result.estimatedArrival());
    }

    /**
     * Our own flight's real progress — DEPARTED/LANDED/CANCELLED — from the {@code flight_instance}. This is
     * the same signal the customer's tracking sees; the provider's {@link #from(String, LocalDate,
     * FlightProviderPort.FlightStatusResult)} only ever reports ON_TIME/DELAYED, so the console uses this
     * once the flight has actually moved.
     */
    public static FlightStatusResponse from(FlightInstance instance) {
        return new FlightStatusResponse(instance.getFlightNo(), instance.getFlightDate(),
                instance.getStatus().name(), instance.getDeparture(), instance.getArrival());
    }
}
