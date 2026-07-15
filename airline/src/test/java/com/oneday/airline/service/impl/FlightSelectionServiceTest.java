package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.domain.LaneRateCard;
import com.oneday.airline.repository.LaneRateCardRepository;
import com.oneday.airline.service.exception.LaneRateCardNotFoundException;
import com.oneday.airline.service.exception.NoFlightAvailableException;
import com.oneday.airline.service.provider.FlightProviderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightSelectionServiceTest {

    @Mock FlightProviderPort flightProviderPort;
    @Mock LaneRateCardRepository laneRateCardRepository;

    private final AirlineProperties properties = new AirlineProperties();   // gateCutoffLeadMinutes = 180
    private final CostEstimator costEstimator = new CostEstimator(properties);

    private FlightSelectionService service() {
        return new FlightSelectionService(flightProviderPort, laneRateCardRepository, costEstimator, properties);
    }

    private LaneRateCard rateCard() {
        LaneRateCard c = new LaneRateCard();
        c.setMinChargePaise(150_000);
        c.setTerminalHandlingPaise(38_000);
        c.setRateBelow45kgPaisePerKg(6_500);
        c.setRateQ45PaisePerKg(5_800);
        c.setRateQ100PaisePerKg(5_200);
        c.setRateQ300PaisePerKg(4_700);
        c.setRateQ500PaisePerKg(4_300);
        c.setRateQ1000PaisePerKg(4_000);
        return c;
    }

    private void stubRateCard() {
        when(laneRateCardRepository.findByOriginHubAndDestHubAndStatus("DEL", "BOM", "ACTIVE"))
                .thenReturn(Optional.of(rateCard()));
    }

    @Test
    void picksTheCheapestCandidateWhoseCutoffIsStillAhead() {
        stubRateCard();
        LocalDate date = LocalDate.of(2026, 7, 20);
        // Ready at 05:00 IST: both slots' cutoffs (departure - 3h) are still ahead.
        var readyAt = ZonedDateTime.of(date, LocalTime.of(5, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", date)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM12", "SIM-CARRIER",
                        LocalTime.of(12, 0), LocalTime.of(14, 0), 2000),
                new FlightProviderPort.FlightCandidate("ODDELBOM18", "SIM-CARRIER",
                        LocalTime.of(18, 0), LocalTime.of(20, 0), 2000)));

        var selection = service().select("DEL", "BOM", readyAt);

        // Same lane/rate card, no overnight discount on either → identical cost; earliest departure wins the tie.
        assertThat(selection.flightNo()).isEqualTo("ODDELBOM12");
        assertThat(selection.flightDate()).isEqualTo(date);
    }

    @Test
    void excludesACandidateWhoseCutoffHasAlreadyPassed() {
        stubRateCard();
        LocalDate date = LocalDate.of(2026, 7, 20);
        // Ready at 10:00 IST: the 12:00 slot's cutoff (09:00) has passed; only 18:00 is catchable.
        var readyAt = ZonedDateTime.of(date, LocalTime.of(10, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", date)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM12", "SIM-CARRIER",
                        LocalTime.of(12, 0), LocalTime.of(14, 0), 2000),
                new FlightProviderPort.FlightCandidate("ODDELBOM18", "SIM-CARRIER",
                        LocalTime.of(18, 0), LocalTime.of(20, 0), 2000)));

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightNo()).isEqualTo("ODDELBOM18");
    }

    @Test
    void prefersTheGenuinelyCheaperOvernightSlotWhenBothAreCatchable() {
        stubRateCard();
        LocalDate date = LocalDate.of(2026, 7, 20);
        var readyAt = ZonedDateTime.of(date, LocalTime.of(5, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", date)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM12", "SIM-CARRIER",
                        LocalTime.of(12, 0), LocalTime.of(14, 0), 2000),
                new FlightProviderPort.FlightCandidate("ODDELBOM22", "SIM-CARRIER",
                        LocalTime.of(22, 0), LocalTime.of(0, 0), 2000)));   // overnight window (default 22:00-06:00)

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightNo()).isEqualTo("ODDELBOM22");
    }

    @Test
    void rollsToTheNextDayWhenEveryCutoffTodayHasPassed() {
        stubRateCard();
        LocalDate today = LocalDate.of(2026, 7, 20);
        LocalDate tomorrow = today.plusDays(1);
        // Ready at 23:00 IST: every slot today has already departed/missed cutoff.
        var readyAt = ZonedDateTime.of(today, LocalTime.of(23, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", today)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM12", "SIM-CARRIER",
                        LocalTime.of(12, 0), LocalTime.of(14, 0), 2000)));
        when(flightProviderPort.search("DEL", "BOM", tomorrow)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM06", "SIM-CARRIER",
                        LocalTime.of(6, 0), LocalTime.of(8, 0), 2000)));

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightDate()).isEqualTo(tomorrow);
        assertThat(selection.flightNo()).isEqualTo("ODDELBOM06");
    }

    @Test
    void missingRateCard_throws() {
        when(laneRateCardRepository.findByOriginHubAndDestHubAndStatus("DEL", "MAA", "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().select("DEL", "MAA", java.time.Instant.now()))
                .isInstanceOf(LaneRateCardNotFoundException.class);
    }

    @Test
    void noScheduledFlightWithinLookahead_throws() {
        stubRateCard();
        when(flightProviderPort.search(eq("DEL"), eq("BOM"), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service().select("DEL", "BOM", java.time.Instant.now()))
                .isInstanceOf(NoFlightAvailableException.class);
    }
}
