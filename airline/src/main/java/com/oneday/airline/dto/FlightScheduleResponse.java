package com.oneday.airline.dto;

import com.oneday.airline.domain.FlightSchedule;

import java.time.LocalTime;

public record FlightScheduleResponse(
        String flightNo,
        String carrier,
        String originHub,
        String destHub,
        LocalTime departureTime,
        LocalTime arrivalTime,
        int capacityKg) {

    public static FlightScheduleResponse from(FlightSchedule s) {
        return new FlightScheduleResponse(s.getFlightNo(), s.getCarrier(), s.getOriginHub(), s.getDestHub(),
                s.getDepartureTime(), s.getArrivalTime(), s.getCapacityKg());
    }
}
