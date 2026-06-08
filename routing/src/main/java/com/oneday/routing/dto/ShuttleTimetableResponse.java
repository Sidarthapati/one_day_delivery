package com.oneday.routing.dto;

import com.oneday.routing.service.model.ShuttleTimetable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Hub↔airport shuttle timetable ({@code GET /routing/shuttle/{cityId}}) — M9's input. */
public record ShuttleTimetableResponse(
        UUID cityId,
        LocalDate validDate,
        int hubToAirportMinutes,
        List<Departure> departures) {

    public record Departure(LocalTime departure, LocalTime arrival) {}

    public static ShuttleTimetableResponse from(ShuttleTimetable t) {
        List<Departure> departures = t.departureTimes().stream()
                .map(d -> new Departure(d, t.arrivalFor(d)))
                .toList();
        return new ShuttleTimetableResponse(t.cityId(), t.validDate(), t.hubToAirportMinutes(), departures);
    }
}
