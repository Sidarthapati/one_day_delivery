package com.oneday.airline.dto;

import com.oneday.airline.consolidator.ConsolidatorFlightLeg;

import java.time.Instant;
import java.time.LocalDate;

public record FlightScheduleResponse(
        String flightNo,
        String carrier,
        String originHub,
        String destHub,
        LocalDate flightDate,
        Instant departureAt,
        Instant arrivalAt,
        int capacityKg,
        String status) {

    public static FlightScheduleResponse from(ConsolidatorFlightLeg leg) {
        return new FlightScheduleResponse(leg.flightNo(), leg.carrier(), leg.originHub(), leg.destHub(),
                leg.flightDate(), leg.departureAt(), leg.arrivalAt(), leg.capacityKg(), leg.status());
    }
}
