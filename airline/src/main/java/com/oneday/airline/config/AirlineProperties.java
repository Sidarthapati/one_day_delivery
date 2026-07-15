package com.oneday.airline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * M9 airline configuration. Engine-wide defaults so no yaml is required to boot, mirroring
 * {@code hub.HubProperties}.
 */
@Component
@ConfigurationProperties(prefix = "airline")
@Data
public class AirlineProperties {

    /**
     * How long before a flight departs a bag must be handed over to the ground handler (the flight's
     * booking cutoff). Mirrors {@code hub.hubDepartureLeadMinutes}; kept as a separate M9 knob since
     * M9 owns the flight side of the cutoff, M7 the bag side. Default 3h.
     */
    private int gateCutoffLeadMinutes = 180;

    /** A flight departing at/after this hour (IST) is in the discounted overnight window. */
    private int overnightWindowStartHour = 22;

    /** A flight departing before this hour (IST) is still in the overnight window (wraps past midnight). */
    private int overnightWindowEndHour = 6;

    /** Discount applied to the overnight-window rate when comparing candidates (basis points off). */
    private int overnightDiscountBps = 1000;

    /**
     * Typical bag weight (grams) used to rank candidate flights by cost during selection, before a
     * bag exists to weigh (§5's "honest limitation" — an estimate, corrected to the real number once
     * the bag is actually sealed and booked).
     */
    private int typicalBagWeightGrams = 50_000;

    /**
     * IATA hub code (e.g. "DEL") → the same fixed cityId UUID used throughout the system (grid.cities,
     * costing_params). Lets {@code FlightCutoffPortAdapter} resolve routing's {@code UUID cityId} back
     * to the hub code {@code flight_schedule} is keyed on. Populated in app's application.yml.
     */
    private Map<String, UUID> cities = new HashMap<>();

    /** How often the status poll job checks booked flights for real time transitions/disruptions. */
    private long statusPollDelayMs = 300_000;   // 5 min

    /**
     * A simulated delay past this many minutes of the original scheduled departure is treated as
     * "breaks the delivery promise" — the reassignment engine moves the bag to a faster flight. Below
     * it, only an advisory time-changed notice goes out; no parcels move.
     */
    private int delayReassignThresholdMinutes = 60;

    public boolean isOvernight(LocalTime departure) {
        LocalTime start = LocalTime.of(overnightWindowStartHour, 0);
        LocalTime end = LocalTime.of(overnightWindowEndHour, 0);
        return start.isAfter(end)
                ? !departure.isBefore(start) || departure.isBefore(end)   // window wraps midnight
                : !departure.isBefore(start) && departure.isBefore(end);
    }
}
