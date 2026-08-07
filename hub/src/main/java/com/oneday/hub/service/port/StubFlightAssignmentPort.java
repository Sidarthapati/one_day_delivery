package com.oneday.hub.service.port;

import com.oneday.hub.config.ClockConfig;
import com.oneday.hub.config.HubProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Deterministic flight assignment until M9 ships (M7-D-009). Four daily <b>departures</b> per
 * destination; the bag must leave the hub {@code hubDepartureLeadMinutes} before departure, so that
 * is the bag cutoff (a flight at 18:00 with a 5h lead ⇒ cutoff 13:00). A parcel rides the earliest
 * slot whose cutoff is still ahead. The real M9 impl swaps in via {@code @Primary}.
 */
@Component
class StubFlightAssignmentPort implements FlightAssignmentPort {

    // Daily flight departure times (IST); the flight lands +2h. The bag cutoff is derived from the lead.
    private static final List<LocalTime> DEPARTURES = List.of(
            LocalTime.of(6, 0), LocalTime.of(12, 0), LocalTime.of(18, 0), LocalTime.of(22, 0));
    private static final long FLIGHT_MINUTES = 120;

    private final HubProperties properties;

    StubFlightAssignmentPort(HubProperties properties) {
        this.properties = properties;
    }

    @Override
    public FlightAssignment assignFlight(String originHub, String destHub, Instant readyAt) {
        ZonedDateTime ready = readyAt.atZone(ClockConfig.IST);
        for (LocalTime departure : DEPARTURES) {
            ZonedDateTime cutoff = cutoff(ready.toLocalDate(), departure);
            if (!cutoff.isBefore(ready)) {
                return assignment(destHub, ready.toLocalDate(), departure);
            }
        }
        // Past the last catchable cutoff today → the first departure tomorrow.
        LocalDate next = ready.toLocalDate().plusDays(1);
        return assignment(destHub, next, DEPARTURES.get(0));
    }

    /** Cutoff = departure − hub-departure lead (when the bag must leave the hub). */
    private ZonedDateTime cutoff(LocalDate date, LocalTime departure) {
        return date.atTime(departure).atZone(ClockConfig.IST)
                .minusMinutes(properties.getHubDepartureLeadMinutes());
    }

    private FlightAssignment assignment(String destHub, LocalDate date, LocalTime departure) {
        String flightNo = "OD%s%02d".formatted(destHub.toUpperCase(), departure.getHour());
        ZonedDateTime dep = date.atTime(departure).atZone(ClockConfig.IST);
        Instant arrival = dep.plusMinutes(FLIGHT_MINUTES).toInstant();
        return new FlightAssignment(flightNo, date, destHub, cutoff(date, departure).toInstant(), arrival);
    }
}
