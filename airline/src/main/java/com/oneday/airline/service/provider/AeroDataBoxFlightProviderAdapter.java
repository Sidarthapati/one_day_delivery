package com.oneday.airline.service.provider;

import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.consolidator.ConsolidatorFlightRepository;
import com.oneday.airline.provider.aerodatabox.AeroDataBoxClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * The real {@link FlightProviderPort} when {@code airline.aerodatabox.enabled=true} — becomes primary,
 * replacing {@code ConsolidatorFlightProviderAdapter}. Deliberately asymmetric on cost:
 *
 * <ul>
 *   <li>{@link #search} reads our own {@code flight_leg} store (populated by the monthly
 *       {@code AeroDataBoxScheduleIngestService}), so flight selection at scan-in costs <b>zero</b>
 *       vendor units;</li>
 *   <li>{@link #status} is the one call that hits the live API — made only by the <b>daily</b>
 *       disruption poll for today/tomorrow flights, bounding paid usage.</li>
 * </ul>
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "airline.aerodatabox", name = "enabled", havingValue = "true")
class AeroDataBoxFlightProviderAdapter implements FlightProviderPort {

    private final ConsolidatorFlightRepository flightLegStore;
    private final AeroDataBoxClient client;

    AeroDataBoxFlightProviderAdapter(ConsolidatorFlightRepository flightLegStore, AeroDataBoxClient client) {
        this.flightLegStore = flightLegStore;
        this.client = client;
    }

    @Override
    public List<FlightCandidate> search(String originHub, String destHub, LocalDate date) {
        return flightLegStore.findLegs(originHub, destHub, date).stream()
                .map(leg -> new FlightCandidate(leg.flightNo(), leg.carrier(),
                        leg.departureAt().atZone(ClockConfig.IST).toLocalTime(),
                        leg.arrivalAt().atZone(ClockConfig.IST).toLocalTime(),
                        leg.capacityKg()))
                .toList();
    }

    @Override
    public FlightStatusResult status(String flightNo, LocalDate flightDate) {
        AeroDataBoxClient.StatusRow row = client.flightStatus(flightNo, flightDate).orElse(null);
        if (row == null) {
            return new FlightStatusResult(FlightRealWorldStatus.ON_TIME, null, null);
        }
        String s = row.rawStatus().toLowerCase();
        if (s.contains("cancel")) {
            return new FlightStatusResult(FlightRealWorldStatus.CANCELLED, row.estimatedDeparture(), row.estimatedArrival());
        }
        // Real in-flight progress — the post-take-off check (§ Task 2) needs the actual arrival, so surface
        // ARRIVED/LANDED and EN-ROUTE/DEPARTED here rather than collapsing them to ON_TIME.
        if (s.contains("arriv") || s.contains("landed")) {
            return new FlightStatusResult(FlightRealWorldStatus.LANDED, row.estimatedDeparture(), row.estimatedArrival());
        }
        if (s.contains("en route") || s.contains("enroute") || s.contains("airborne") || s.contains("departed")) {
            return new FlightStatusResult(FlightRealWorldStatus.DEPARTED, row.estimatedDeparture(), row.estimatedArrival());
        }
        if (s.contains("delay") && row.estimatedDeparture() != null) {
            return new FlightStatusResult(FlightRealWorldStatus.DELAYED, row.estimatedDeparture(), row.estimatedArrival());
        }
        return new FlightStatusResult(FlightRealWorldStatus.ON_TIME, row.estimatedDeparture(), row.estimatedArrival());
    }
}
