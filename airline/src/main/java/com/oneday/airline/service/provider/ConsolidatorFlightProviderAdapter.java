package com.oneday.airline.service.provider;

import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.consolidator.ConsolidatorFlightLeg;
import com.oneday.airline.consolidator.ConsolidatorFlightRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * The real answer to {@link FlightProviderPort}, backed by the freight consolidator's (mocked)
 * production schema — read-only, direct DB access, no vendor API (see {@code
 * ConsolidatorDataSourceConfig}). Replaces the earlier {@code SimFlightProviderAdapter}, which
 * simulated a vendor by reading our own {@code flight_schedule}/recurring-pattern tables; those are
 * gone now that the consolidator's own dated calendar is the schedule source of truth.
 */
@Component
class ConsolidatorFlightProviderAdapter implements FlightProviderPort {

    private final ConsolidatorFlightRepository consolidatorFlightRepository;

    ConsolidatorFlightProviderAdapter(ConsolidatorFlightRepository consolidatorFlightRepository) {
        this.consolidatorFlightRepository = consolidatorFlightRepository;
    }

    @Override
    public List<FlightCandidate> search(String originHub, String destHub, LocalDate date) {
        return consolidatorFlightRepository.findLegs(originHub, destHub, date).stream()
                .map(leg -> new FlightCandidate(leg.flightNo(), leg.carrier(),
                        leg.departureAt().atZone(ClockConfig.IST).toLocalTime(),
                        leg.arrivalAt().atZone(ClockConfig.IST).toLocalTime(),
                        leg.capacityKg()))
                .toList();
    }

    @Override
    public FlightStatusResult status(String flightNo, LocalDate flightDate) {
        ConsolidatorFlightLeg leg = consolidatorFlightRepository.findLeg(flightNo, flightDate);
        FlightRealWorldStatus status = switch (leg.status()) {
            case "CANCELLED" -> FlightRealWorldStatus.CANCELLED;
            case "DELAYED" -> FlightRealWorldStatus.DELAYED;
            default -> FlightRealWorldStatus.ON_TIME;
        };
        return switch (status) {
            case DELAYED -> new FlightStatusResult(status, leg.estimatedDepartureAt(), leg.estimatedArrivalAt());
            default -> new FlightStatusResult(status, leg.departureAt(), leg.arrivalAt());
        };
    }
}
