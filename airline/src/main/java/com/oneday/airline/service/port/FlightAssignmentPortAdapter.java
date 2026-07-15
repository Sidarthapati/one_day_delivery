package com.oneday.airline.service.port;

import com.oneday.airline.service.impl.FlightSelectionService;
import com.oneday.hub.service.port.FlightAssignmentPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The real M9 answer to M7's "which flight should this batch use?" (§7.1), replacing {@code
 * StubFlightAssignmentPort} now that M9 has shipped (M7-D-009).
 */
@Component
@Primary
class FlightAssignmentPortAdapter implements FlightAssignmentPort {

    private final FlightSelectionService flightSelectionService;

    FlightAssignmentPortAdapter(FlightSelectionService flightSelectionService) {
        this.flightSelectionService = flightSelectionService;
    }

    @Override
    public FlightAssignment assignFlight(String originHub, String destHub, Instant readyAt) {
        FlightSelectionService.Selection selection = flightSelectionService.select(originHub, destHub, readyAt);
        return new FlightAssignment(selection.flightNo(), selection.flightDate(), selection.destHub(),
                selection.cutoff(), selection.arrival());
    }
}
