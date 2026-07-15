package com.oneday.airline.service.port;

import com.oneday.airline.service.impl.FlightSelectionService;
import com.oneday.hub.service.port.FlightAssignmentPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightAssignmentPortAdapterTest {

    @Mock FlightSelectionService flightSelectionService;

    @Test
    void mapsTheSelectionOntoHubsFlightAssignmentContract() {
        Instant readyAt = Instant.parse("2026-07-20T02:00:00Z");
        Instant departure = Instant.parse("2026-07-20T06:30:00Z");
        Instant arrival = Instant.parse("2026-07-20T08:30:00Z");
        Instant cutoff = Instant.parse("2026-07-20T03:30:00Z");
        var selection = new FlightSelectionService.Selection(
                "ODDELBOM12", LocalDate.of(2026, 7, 20), "DEL", "BOM", departure, arrival, cutoff, 2000);
        when(flightSelectionService.select("DEL", "BOM", readyAt)).thenReturn(selection);

        FlightAssignmentPort.FlightAssignment assignment =
                new FlightAssignmentPortAdapter(flightSelectionService).assignFlight("DEL", "BOM", readyAt);

        assertThat(assignment.flightNo()).isEqualTo("ODDELBOM12");
        assertThat(assignment.flightDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(assignment.destHub()).isEqualTo("BOM");
        assertThat(assignment.bagCutoff()).isEqualTo(cutoff);
        assertThat(assignment.arrival()).isEqualTo(arrival);
    }
}
