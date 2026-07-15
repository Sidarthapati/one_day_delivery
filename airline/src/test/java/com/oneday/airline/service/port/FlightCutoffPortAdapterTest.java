package com.oneday.airline.service.port;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.service.exception.LaneRateCardNotFoundException;
import com.oneday.airline.service.impl.FlightSelectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightCutoffPortAdapterTest {

    @Mock FlightSelectionService flightSelectionService;

    private final UUID delhiId = UUID.randomUUID();
    private final UUID mumbaiId = UUID.randomUUID();
    private final UUID bengaluruId = UUID.randomUUID();

    private AirlineProperties properties() {
        AirlineProperties props = new AirlineProperties();
        Map<String, UUID> cities = new HashMap<>();
        cities.put("DEL", delhiId);
        cities.put("BOM", mumbaiId);
        cities.put("BLR", bengaluruId);
        props.setCities(cities);
        return props;
    }

    private FlightSelectionService.Selection selectionWithCutoff(String dest, Instant cutoff) {
        return new FlightSelectionService.Selection("FL-" + dest, LocalDate.of(2026, 7, 20), "DEL", dest,
                cutoff.plusSeconds(3600), cutoff.plusSeconds(7200), cutoff, 2000);
    }

    @Test
    void unknownCityId_returnsEmptyWithoutCallingSelection() {
        var adapter = new FlightCutoffPortAdapter(flightSelectionService, properties());

        Optional<Instant> result = adapter.outboundFlightCutoff(UUID.randomUUID(), LocalDate.of(2026, 7, 20));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsTheEarliestCutoffAcrossEveryOutboundLane() {
        AirlineProperties props = properties();
        LocalDate date = LocalDate.of(2026, 7, 20);
        Instant laterCutoff = Instant.parse("2026-07-20T05:00:00Z");
        Instant earlierCutoff = Instant.parse("2026-07-20T02:00:00Z");
        when(flightSelectionService.select(eq("DEL"), eq("BOM"), any())).thenReturn(selectionWithCutoff("BOM", laterCutoff));
        when(flightSelectionService.select(eq("DEL"), eq("BLR"), any())).thenReturn(selectionWithCutoff("BLR", earlierCutoff));

        Optional<Instant> result = new FlightCutoffPortAdapter(flightSelectionService, props)
                .outboundFlightCutoff(delhiId, date);

        assertThat(result).contains(earlierCutoff);
    }

    @Test
    void aLaneWithNoRateCardConfigured_isSkippedRatherThanFailingTheWholeLookup() {
        AirlineProperties props = properties();
        LocalDate date = LocalDate.of(2026, 7, 20);
        Instant onlyCutoff = Instant.parse("2026-07-20T05:00:00Z");
        when(flightSelectionService.select(eq("DEL"), eq("BOM"), any())).thenReturn(selectionWithCutoff("BOM", onlyCutoff));
        when(flightSelectionService.select(eq("DEL"), eq("BLR"), any()))
                .thenThrow(new LaneRateCardNotFoundException("DEL", "BLR"));

        Optional<Instant> result = new FlightCutoffPortAdapter(flightSelectionService, props)
                .outboundFlightCutoff(delhiId, date);

        assertThat(result).contains(onlyCutoff);
    }
}
