package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.consolidator.ConsolidatorLaneRate;
import com.oneday.airline.consolidator.ConsolidatorRateRepository;
import com.oneday.airline.service.exception.NoFlightAvailableException;
import com.oneday.airline.service.provider.FlightProviderPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * The shared brain behind both synchronous seams (hub's {@code FlightAssignmentPort}, routing's
 * {@code FlightCutoffPort}): pick the cheapest flight on a lane that a parcel can catch and that still
 * meets the delivery promise, rolling to the next day if today's cutoffs have all passed (§5).
 *
 * <p>Two objectives, in order: (1) <b>meet the {@code slaHours} promise</b> — only flights arriving
 * within the parcel's SLA window are eligible; if none can (a very late booking), ship on the
 * soonest-arriving catchable flight as best effort (M9 doc §11). (2) <b>Consolidate + avoid prime</b>
 * — among eligible flights, pick the cheapest, breaking ties toward the <em>latest</em> departure so
 * parcels accumulate onto the fullest flight (one AWB per plane); the prime-window surcharge makes the
 * expensive 00:00–09:00 slot lose on cost unless it's the only option.</p>
 *
 * <p>Read-only — it persists nothing; a {@code flight_instance} row is only created lazily when a bag
 * actually books (mirrors M7's lazy bag-open pattern).</p>
 */
@Component
public class FlightSelectionService {

    /** Safety bound on how many days ahead to search before treating the lane as misconfigured. */
    private static final int MAX_LOOKAHEAD_DAYS = 7;

    private final FlightProviderPort flightProviderPort;
    private final ConsolidatorRateRepository consolidatorRateRepository;
    private final CostEstimator costEstimator;
    private final AirlineProperties properties;

    FlightSelectionService(FlightProviderPort flightProviderPort, ConsolidatorRateRepository consolidatorRateRepository,
                            CostEstimator costEstimator, AirlineProperties properties) {
        this.flightProviderPort = flightProviderPort;
        this.consolidatorRateRepository = consolidatorRateRepository;
        this.costEstimator = costEstimator;
        this.properties = properties;
    }

    /** The best flight from {@code originHub} to {@code destHub} for a parcel ready at {@code readyAt}. */
    public Selection select(String originHub, String destHub, Instant readyAt) {
        ConsolidatorLaneRate rateCard = consolidatorRateRepository.findActiveRate(originHub, destHub);

        ZonedDateTime ready = readyAt.atZone(ClockConfig.IST);
        LocalDate date = ready.toLocalDate();
        Instant slaDeadline = readyAt.plus(properties.getSlaHours(), ChronoUnit.HOURS);

        for (int daysAhead = 0; daysAhead < MAX_LOOKAHEAD_DAYS; daysAhead++) {
            LocalDate candidateDate = date.plusDays(daysAhead);
            List<FlightProviderPort.FlightCandidate> candidates =
                    flightProviderPort.search(originHub, destHub, candidateDate);

            // Day 0 must still be catchable (cutoff ahead of readyAt); later days are trivially catchable.
            boolean cutoffMustBeAhead = daysAhead == 0;
            List<Priced> catchable = candidates.stream()
                    .map(c -> price(c, candidateDate, rateCard))
                    .filter(p -> !cutoffMustBeAhead || !p.cutoff().isBefore(readyAt))
                    .toList();

            // First day with anything catchable decides it: a later day can only arrive even later, so it
            // never beats this day on either the SLA or "soonest". Roll forward only when today is empty.
            if (catchable.isEmpty()) {
                continue;
            }

            // (1) Prefer flights that meet the SLA promise; among them (2) cheapest, tie → latest departure
            // (consolidation: fill one flight fuller). Prime-window flights are pricier so they lose on cost.
            List<Priced> withinSla = catchable.stream()
                    .filter(p -> !p.arrival().isAfter(slaDeadline))
                    .toList();
            Priced best = !withinSla.isEmpty()
                    ? withinSla.stream()
                        .min(Comparator.comparingLong(Priced::costPaise)
                                .thenComparing(Priced::departure, Comparator.reverseOrder()))
                        .orElseThrow()
                    // No flight can meet the promise (very late booking): ship the soonest-arriving one.
                    : catchable.stream().min(Comparator.comparing(Priced::arrival)).orElseThrow();
            return toSelection(best, originHub, destHub);
        }
        throw new NoFlightAvailableException(originHub, destHub);
    }

    private Priced price(FlightProviderPort.FlightCandidate candidate, LocalDate date, ConsolidatorLaneRate rateCard) {
        ZonedDateTime departure = date.atTime(candidate.departureTime()).atZone(ClockConfig.IST);
        ZonedDateTime arrival = date.atTime(candidate.arrivalTime()).atZone(ClockConfig.IST);
        if (!candidate.arrivalTime().isAfter(candidate.departureTime())) {
            arrival = arrival.plusDays(1);   // overnight-spanning flight
        }
        Instant cutoff = departure.minusMinutes(properties.getGateCutoffLeadMinutes()).toInstant();
        boolean prime = properties.isPrime(candidate.departureTime());
        long costPaise = costEstimator.estimatePaise(rateCard, properties.getTypicalBagWeightGrams(), prime);
        return new Priced(candidate, date, departure.toInstant(), arrival.toInstant(), cutoff, costPaise);
    }

    private Selection toSelection(Priced p, String originHub, String destHub) {
        return new Selection(p.candidate().flightNo(), p.flightDate(), originHub, destHub,
                p.departure(), p.arrival(), p.cutoff(), p.candidate().capacityKg());
    }

    private record Priced(FlightProviderPort.FlightCandidate candidate, LocalDate flightDate, Instant departure,
                           Instant arrival, Instant cutoff, long costPaise) {
    }

    public record Selection(String flightNo, LocalDate flightDate, String originHub, String destHub,
                             Instant departure, Instant arrival, Instant cutoff, int capacityKg) {
    }
}
