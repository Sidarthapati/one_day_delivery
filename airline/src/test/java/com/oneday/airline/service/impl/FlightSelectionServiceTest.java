package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.consolidator.ConsolidatorLaneRate;
import com.oneday.airline.consolidator.ConsolidatorRateRepository;
import com.oneday.airline.service.exception.ConsolidatorRateNotFoundException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightSelectionServiceTest {

    @Mock FlightProviderPort flightProviderPort;
    @Mock ConsolidatorRateRepository consolidatorRateRepository;

    private final AirlineProperties properties = new AirlineProperties();   // gateCutoffLeadMinutes = 180
    private final CostEstimator costEstimator = new CostEstimator(properties);

    private FlightSelectionService service() {
        return new FlightSelectionService(flightProviderPort, consolidatorRateRepository, costEstimator, properties);
    }

    private ConsolidatorLaneRate rateCard() {
        return new ConsolidatorLaneRate("DEL", "BOM",
                150_000, 38_000,
                6_500, 5_800, 5_200, 4_700, 4_300, 4_000);
    }

    private void stubRateCard() {
        when(consolidatorRateRepository.findActiveRate("DEL", "BOM")).thenReturn(rateCard());
    }

    @Test
    void consolidatesOntoTheLatestFlightThatStillMeetsTheSla() {
        stubRateCard();
        LocalDate date = LocalDate.of(2026, 7, 20);
        // Ready 05:00 IST → SLA deadline 21:00. Both non-prime slots meet it at equal cost; the LATER
        // one wins so parcels pile onto the fullest flight (one AWB per plane).
        var readyAt = ZonedDateTime.of(date, LocalTime.of(5, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", date)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM12", "SIM-CARRIER",
                        LocalTime.of(12, 0), LocalTime.of(14, 0), 2000),
                new FlightProviderPort.FlightCandidate("ODDELBOM18", "SIM-CARRIER",
                        LocalTime.of(18, 0), LocalTime.of(20, 0), 2000)));

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightNo()).isEqualTo("ODDELBOM18");
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
    void avoidsThePrimeWindowFlightWhenACheaperNonPrimeMeetsTheSla() {
        stubRateCard();
        LocalDate date = LocalDate.of(2026, 7, 20);
        // Ready 02:00 IST → SLA deadline 18:00. The 06:00 slot is in the prime window (00:00–09:00, +35%);
        // the 12:00 non-prime is cheaper, so it wins even though 06:00 is earlier and also catchable.
        var readyAt = ZonedDateTime.of(date, LocalTime.of(2, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", date)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM06", "SIM-CARRIER",
                        LocalTime.of(6, 0), LocalTime.of(8, 0), 2000),    // prime, +35%
                new FlightProviderPort.FlightCandidate("ODDELBOM12", "SIM-CARRIER",
                        LocalTime.of(12, 0), LocalTime.of(14, 0), 2000)));

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightNo()).isEqualTo("ODDELBOM12");
    }

    @Test
    void takesThePrimeFlightWhenItIsTheOnlyOneMeetingTheSla() {
        stubRateCard();
        LocalDate today = LocalDate.of(2026, 7, 20);
        LocalDate tomorrow = today.plusDays(1);
        // Ready 23:00 → deadline 15:00 tomorrow; today's cutoffs all passed. Tomorrow a 06:00 prime flight
        // arrives 08:00 (meets SLA) while a 22:00 non-prime arrives 00:00 the day after (misses it) — so the
        // pricey prime slot is taken because it's the only one that keeps the promise (avoid-unless-only).
        var readyAt = ZonedDateTime.of(today, LocalTime.of(23, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", today)).thenReturn(List.of());
        when(flightProviderPort.search("DEL", "BOM", tomorrow)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM06", "SIM-CARRIER",
                        LocalTime.of(6, 0), LocalTime.of(8, 0), 2000),     // prime, meets SLA
                new FlightProviderPort.FlightCandidate("ODDELBOM22", "SIM-CARRIER",
                        LocalTime.of(22, 0), LocalTime.of(0, 0), 2000)));  // non-prime, arrives past deadline

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightDate()).isEqualTo(tomorrow);
        assertThat(selection.flightNo()).isEqualTo("ODDELBOM06");
    }

    @Test
    void shipsTheSoonestFlightWhenNoFlightCanMeetTheSla() {
        stubRateCard();
        LocalDate date = LocalDate.of(2026, 7, 20);
        // Ready 05:00 → deadline 21:00. The only catchable flight departs 22:00 and arrives 00:00 next day,
        // past the SLA. It still ships (best effort, minimise lateness) rather than failing outright.
        var readyAt = ZonedDateTime.of(date, LocalTime.of(5, 0), ClockConfig.IST).toInstant();
        when(flightProviderPort.search("DEL", "BOM", date)).thenReturn(List.of(
                new FlightProviderPort.FlightCandidate("ODDELBOM22", "SIM-CARRIER",
                        LocalTime.of(22, 0), LocalTime.of(0, 0), 2000)));

        var selection = service().select("DEL", "BOM", readyAt);

        assertThat(selection.flightNo()).isEqualTo("ODDELBOM22");
        assertThat(selection.flightDate()).isEqualTo(date);
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
        when(consolidatorRateRepository.findActiveRate("DEL", "MAA"))
                .thenThrow(new ConsolidatorRateNotFoundException("DEL", "MAA"));

        assertThatThrownBy(() -> service().select("DEL", "MAA", java.time.Instant.now()))
                .isInstanceOf(ConsolidatorRateNotFoundException.class);
    }

    @Test
    void noScheduledFlightWithinLookahead_throws() {
        stubRateCard();
        when(flightProviderPort.search(eq("DEL"), eq("BOM"), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service().select("DEL", "BOM", java.time.Instant.now()))
                .isInstanceOf(NoFlightAvailableException.class);
    }
}
