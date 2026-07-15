package com.oneday.airline.service.exception;

/** A booking referenced a flight number with no matching {@code flight_schedule} row. Shouldn't
 * happen in practice — the flight number always originates from M9's own selection — but a bag-seal
 * notification could in principle name a flight M9 has since deactivated. */
public class FlightScheduleNotFoundException extends RuntimeException {
    public FlightScheduleNotFoundException(String flightNo) {
        super("Unknown flight number: " + flightNo);
    }
}
