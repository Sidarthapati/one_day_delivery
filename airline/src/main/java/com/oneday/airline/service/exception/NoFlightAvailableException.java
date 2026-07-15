package com.oneday.airline.service.exception;

/**
 * No scheduled flight was found on a lane within the lookahead window — a misconfigured/empty
 * timetable, not the normal "cutoff already passed today" case (that just rolls to tomorrow, §5).
 */
public class NoFlightAvailableException extends RuntimeException {
    public NoFlightAvailableException(String originHub, String destHub) {
        super("No scheduled flight found for lane %s→%s within the lookahead window".formatted(originHub, destHub));
    }
}
