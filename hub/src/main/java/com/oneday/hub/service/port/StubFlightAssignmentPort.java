package com.oneday.hub.service.port;

import com.oneday.hub.config.ClockConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic flight assignment until M9 ships (M7-D-009). Four daily slots per destination; a
 * parcel rides the earliest slot whose bag cutoff is still ahead. The real M9 impl swaps in via
 * {@code @Primary}.
 */
@Component
class StubFlightAssignmentPort implements FlightAssignmentPort {

    // Bag cutoffs (IST) for the four daily slots; the flight departs at the cutoff, lands +2h.
    private static final List<LocalTime> SLOTS = List.of(
            LocalTime.of(6, 0), LocalTime.of(12, 0), LocalTime.of(18, 0), LocalTime.of(22, 0));
    private static final long FLIGHT_MINUTES = 120;

    @Override
    public FlightAssignment assignFlight(String destHub, Instant readyAt) {
        ZonedDateTime ready = readyAt.atZone(ClockConfig.IST);
        for (LocalTime slot : SLOTS) {
            ZonedDateTime cutoff = ready.toLocalDate().atTime(slot).atZone(ClockConfig.IST);
            if (!cutoff.isBefore(ready)) {
                return assignment(destHub, ready.toLocalDate(), slot, cutoff);
            }
        }
        // Past the last slot today → the first slot tomorrow.
        LocalDate next = ready.toLocalDate().plusDays(1);
        ZonedDateTime cutoff = next.atTime(SLOTS.get(0)).atZone(ClockConfig.IST);
        return assignment(destHub, next, SLOTS.get(0), cutoff);
    }

    @Override
    public Optional<FlightAssignment> nextFlightAfter(FlightAssignment current, String destHub) {
        // The slot strictly after the current cutoff.
        Instant probe = current.bagCutoff().plusSeconds(60);
        return Optional.of(assignFlight(destHub, probe));
    }

    private FlightAssignment assignment(String destHub, LocalDate date, LocalTime slot, ZonedDateTime cutoff) {
        String flightNo = "OD%s%02d".formatted(destHub.toUpperCase(), slot.getHour());
        Instant arrival = cutoff.plusMinutes(FLIGHT_MINUTES).toInstant();
        return new FlightAssignment(flightNo, date, destHub, cutoff.toInstant(), arrival);
    }
}
