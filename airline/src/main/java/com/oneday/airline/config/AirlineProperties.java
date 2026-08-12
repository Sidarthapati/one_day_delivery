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

    /**
     * Start hour (IST, inclusive) of the <b>prime</b> air-cargo window — the expensive overnight rate
     * the consolidator charges. A flight departing in {@code [primeWindowStartHour, primeWindowEndHour)}
     * carries the {@link #primeSurchargeBps} surcharge, so cheapest-first selection avoids it unless it's
     * the only flight that meets the promise. Default window 00:00–09:00.
     */
    private int primeWindowStartHour = 0;

    /** End hour (IST, exclusive) of the prime window. Default 09:00 (so 09:00–23:59 is the cheap rate). */
    private int primeWindowEndHour = 9;

    /** Surcharge applied to a prime-window flight's rate when comparing candidates (basis points on top). Default 35%. */
    private int primeSurchargeBps = 3500;

    /**
     * The internal delivery-promise horizon (hours from a parcel being hub-ready) used as the flight
     * cutoff for <b>leeway batching</b>: any flight arriving within this window is eligible, and among
     * those the fullest (latest-departing) is preferred so parcels consolidate onto one AWB per plane.
     * Default 16h.
     */
    private int slaHours = 16;

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
     * How long after take-off the corrective vendor check starts re-fetching a flight's real arrival
     * (§ Task 2). From here it re-checks every poll cycle ({@link #statusPollDelayMs}, ~5 min) until the
     * flight lands, so the stored arrival — and the LANDED flip, and the arrival shown to the GHA console
     * and the customer — tracks the vendor as it revises the ETA rather than the schedule captured at
     * booking. Default 60 min.
     */
    private int inflightCheckDelayMinutes = 60;

    /**
     * A simulated delay past this many minutes of the original scheduled departure is treated as
     * "breaks the delivery promise" — the reassignment engine moves the bag to a faster flight. Below
     * it, only an advisory time-changed notice goes out; no parcels move.
     */
    private int delayReassignThresholdMinutes = 60;

    /**
     * Tiered disruption polling — poll frequency scales with proximity to departure so a cancellation
     * is caught while there's still time to rebook before cutoff (correctness), while far-out flights
     * cost nothing. The two windows are disjoint and each is swept at its own cadence:
     * <ul>
     *   <li>0 → {@code disruptionImminentWindowHours} before departure: swept every ~30 min;</li>
     *   <li>that up to {@code disruptionUpcomingWindowHours}: swept every ~3 h;</li>
     *   <li>beyond it: not polled — the schedule stands until the flight enters the upcoming window.</li>
     * </ul>
     */
    private int disruptionImminentWindowHours = 6;
    private int disruptionUpcomingWindowHours = 24;

    /** True if a flight departing at this IST time falls in the expensive prime window (§ prime-rate avoidance). */
    public boolean isPrime(LocalTime departure) {
        LocalTime start = LocalTime.of(primeWindowStartHour, 0);
        LocalTime end = LocalTime.of(primeWindowEndHour, 0);
        return start.isAfter(end)
                ? !departure.isBefore(start) || departure.isBefore(end)   // window wraps midnight
                : !departure.isBefore(start) && departure.isBefore(end);
    }
}
