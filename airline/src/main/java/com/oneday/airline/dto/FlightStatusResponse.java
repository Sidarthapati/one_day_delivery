package com.oneday.airline.dto;

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
}
