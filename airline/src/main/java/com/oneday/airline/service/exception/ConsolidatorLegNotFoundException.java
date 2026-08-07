package com.oneday.airline.service.exception;

import java.time.LocalDate;

/** No matching row in the consolidator's (mocked) {@code flight_leg} table for a (flightNo, date)
 * pair. Shouldn't happen in practice — the flight number/date always originate from M9's own
 * selection against the same table — but the consolidator's published calendar could in principle
 * drop a leg between selection and booking. */
public class ConsolidatorLegNotFoundException extends RuntimeException {
    public ConsolidatorLegNotFoundException(String flightNo, LocalDate flightDate) {
        super("No consolidator flight leg for %s on %s".formatted(flightNo, flightDate));
    }
}
