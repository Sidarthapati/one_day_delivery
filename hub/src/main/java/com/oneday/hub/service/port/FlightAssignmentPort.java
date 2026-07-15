package com.oneday.hub.service.port;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Seam to M9 (airline) — <b>read-only</b>. Resolves the flight a parcel rides and that flight's bag
 * cutoff, given the destination and the time the parcel is ready at the origin hub (§7.1). It has no
 * "next flight" query: M7 never picks a replacement flight — reschedules are M9-decided and arrive
 * as {@code FLIGHT_REASSIGNED} events (M7-D-006). M9 is unbuilt, so {@link StubFlightAssignmentPort}
 * returns a deterministic assignment; the real M9 impl swaps in via {@code @Primary}. M7-D-009.
 */
public interface FlightAssignmentPort {

    /** The earliest flight from {@code originHub} to {@code destHub} that a parcel ready at {@code readyAt} can make. */
    FlightAssignment assignFlight(String originHub, String destHub, Instant readyAt);

    /** An assigned flight + its bag cutoff. {@code destHub} is the destination city code. */
    record FlightAssignment(
            String flightNo,
            LocalDate flightDate,
            String destHub,
            Instant bagCutoff,
            Instant arrival) {
    }
}
