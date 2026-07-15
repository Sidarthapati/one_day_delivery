package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.domain.LaneRateCard;
import com.oneday.airline.repository.LaneRateCardRepository;
import com.oneday.airline.service.exception.LaneRateCardNotFoundException;
import com.oneday.airline.service.exception.NoFlightAvailableException;
import com.oneday.airline.service.provider.FlightProviderPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * The shared brain behind both synchronous seams (hub's {@code FlightAssignmentPort}, routing's
 * {@code FlightCutoffPort}): pick the cheapest flight on a lane that still meets cutoff, preferring
 * a genuinely-discounted overnight slot, rolling to the next day if today's cutoffs have all passed
 * (§5). Read-only — it persists nothing; a {@code flight_instance} row is only created lazily when a
 * bag actually books (mirrors M7's lazy bag-open pattern).
 */
@Component
public class FlightSelectionService {

    /** Safety bound on how many days ahead to search before treating the lane as misconfigured. */
    private static final int MAX_LOOKAHEAD_DAYS = 7;

    private final FlightProviderPort flightProviderPort;
    private final LaneRateCardRepository laneRateCardRepository;
    private final CostEstimator costEstimator;
    private final AirlineProperties properties;

    FlightSelectionService(FlightProviderPort flightProviderPort, LaneRateCardRepository laneRateCardRepository,
                            CostEstimator costEstimator, AirlineProperties properties) {
        this.flightProviderPort = flightProviderPort;
        this.laneRateCardRepository = laneRateCardRepository;
        this.costEstimator = costEstimator;
        this.properties = properties;
    }

    /** The cheapest flight from {@code originHub} to {@code destHub} that a parcel ready at {@code readyAt} can make. */
    public Selection select(String originHub, String destHub, Instant readyAt) {
        LaneRateCard rateCard = laneRateCardRepository
                .findByOriginHubAndDestHubAndStatus(originHub, destHub, "ACTIVE")
                .orElseThrow(() -> new LaneRateCardNotFoundException(originHub, destHub));

        ZonedDateTime ready = readyAt.atZone(ClockConfig.IST);
        LocalDate date = ready.toLocalDate();

        for (int daysAhead = 0; daysAhead < MAX_LOOKAHEAD_DAYS; daysAhead++) {
            LocalDate candidateDate = date.plusDays(daysAhead);
            List<FlightProviderPort.FlightCandidate> candidates =
                    flightProviderPort.search(originHub, destHub, candidateDate);

            // Day 0 must still be catchable (cutoff ahead of readyAt); later days are trivially catchable.
            boolean cutoffMustBeAhead = daysAhead == 0;
            List<Priced> priced = candidates.stream()
                    .map(c -> price(c, candidateDate, rateCard))
                    .filter(p -> !cutoffMustBeAhead || !p.cutoff().isBefore(readyAt))
                    .toList();

            if (!priced.isEmpty()) {
                Priced best = priced.stream()
                        .min(Comparator.comparingLong(Priced::costPaise).thenComparing(Priced::departure))
                        .orElseThrow();
                return new Selection(best.candidate().flightNo(), candidateDate, originHub, destHub,
                        best.departure(), best.arrival(), best.cutoff(), best.candidate().capacityKg());
            }
        }
        throw new NoFlightAvailableException(originHub, destHub);
    }

    private Priced price(FlightProviderPort.FlightCandidate candidate, LocalDate date, LaneRateCard rateCard) {
        ZonedDateTime departure = date.atTime(candidate.departureTime()).atZone(ClockConfig.IST);
        ZonedDateTime arrival = date.atTime(candidate.arrivalTime()).atZone(ClockConfig.IST);
        if (!candidate.arrivalTime().isAfter(candidate.departureTime())) {
            arrival = arrival.plusDays(1);   // overnight-spanning flight
        }
        Instant cutoff = departure.minusMinutes(properties.getGateCutoffLeadMinutes()).toInstant();
        boolean overnight = properties.isOvernight(candidate.departureTime());
        long costPaise = costEstimator.estimatePaise(rateCard, properties.getTypicalBagWeightGrams(), overnight);
        return new Priced(candidate, departure.toInstant(), arrival.toInstant(), cutoff, costPaise);
    }

    private record Priced(FlightProviderPort.FlightCandidate candidate, Instant departure, Instant arrival,
                           Instant cutoff, long costPaise) {
    }

    public record Selection(String flightNo, LocalDate flightDate, String originHub, String destHub,
                             Instant departure, Instant arrival, Instant cutoff, int capacityKg) {
    }
}
