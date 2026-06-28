package com.oneday.hub.service.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Seam to M9 (airline). Resolves the flight a parcel rides and that flight's bag cutoff, given the
 * destination and the time the parcel is ready at the origin hub (§7.1). M9 is unbuilt, so
 * {@link StubFlightAssignmentPort} returns a deterministic assignment; the real M9 impl swaps in
 * via {@code @Primary} (mirrors routing's {@code FlightCutoffPort}). M7-D-009.
 */
public interface FlightAssignmentPort {

    /** The earliest flight to {@code destHub} that a parcel ready at {@code readyAt} can make. */
    FlightAssignment assignFlight(String destHub, Instant readyAt);

    /** The next flight to {@code destHub} after {@code current} — used by the §9 reschedule (PR #3). */
    Optional<FlightAssignment> nextFlightAfter(FlightAssignment current, String destHub);

    /** An assigned flight + its bag cutoff. {@code destHub} is the destination city code. */
    record FlightAssignment(
            String flightNo,
            LocalDate flightDate,
            String destHub,
            Instant bagCutoff,
            Instant arrival) {
    }
}
