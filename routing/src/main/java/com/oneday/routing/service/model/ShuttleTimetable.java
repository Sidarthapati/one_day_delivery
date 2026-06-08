package com.oneday.routing.service.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The periodic hub↔airport shuttle timetable for one city/date (§9). A single origin–destination
 * leg expressed as a list of departure times across the operating window plus the OSRM hub→airport
 * travel time; arrival ETAs are {@code departure + hubToAirportMinutes}. Computed on demand, not
 * persisted (there is no shuttle table — §17.3) and published as {@code SHUTTLE_SCHEDULED}.
 */
public record ShuttleTimetable(
        UUID cityId,
        LocalDate validDate,
        List<LocalTime> departureTimes,
        int hubToAirportMinutes) {

    /** Arrival ETA for a given departure ({@code departure + hubToAirportMinutes}). */
    public LocalTime arrivalFor(LocalTime departure) {
        return departure.plusMinutes(hubToAirportMinutes);
    }
}
