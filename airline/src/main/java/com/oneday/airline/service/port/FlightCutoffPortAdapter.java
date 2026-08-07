package com.oneday.airline.service.port;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.service.impl.FlightSelectionService;
import com.oneday.routing.service.port.FlightCutoffPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.oneday.airline.config.ClockConfig.IST;

/**
 * The real M9 answer to M6's "what's the latest hand-off time for this city?" (§12.2, the Q2 budget),
 * replacing {@code NoOpFlightCutoffPort} now that M9 has shipped. Routing knows cities only as a
 * {@code cityId} UUID; {@code airline.cities} (the same map as {@code grid.cities}) resolves it back
 * to the IATA hub code {@code flight_schedule} is keyed on. There's no notion of a destination here —
 * this reports the earliest outbound cutoff <em>from</em> the city, across every lane out of it, since
 * routing needs one hub-arrival deadline regardless of which lane a parcel ultimately rides.
 */
@Component
@Primary
class FlightCutoffPortAdapter implements FlightCutoffPort {

    private final FlightSelectionService flightSelectionService;
    private final AirlineProperties properties;

    FlightCutoffPortAdapter(FlightSelectionService flightSelectionService, AirlineProperties properties) {
        this.flightSelectionService = flightSelectionService;
        this.properties = properties;
    }

    @Override
    public Optional<Instant> outboundFlightCutoff(UUID cityId, LocalDate date) {
        String originHub = originHubFor(cityId);
        if (originHub == null) {
            return Optional.empty();
        }
        // "Ready" = the start of the given date in IST; the earliest cutoff across every outbound
        // lane is the tightest hand-off deadline a first-mile parcel destined anywhere must meet.
        Instant readyAt = date.atStartOfDay(IST).toInstant();
        Instant earliest = null;
        for (String destHub : properties.getCities().keySet()) {
            if (destHub.equals(originHub)) {
                continue;
            }
            try {
                Instant cutoff = flightSelectionService.select(originHub, destHub, readyAt).cutoff();
                if (earliest == null || cutoff.isBefore(earliest)) {
                    earliest = cutoff;
                }
            } catch (RuntimeException ignored) {
                // A misconfigured/unpriced lane shouldn't block the other lanes' cutoffs.
            }
        }
        return Optional.ofNullable(earliest);
    }

    private String originHubFor(UUID cityId) {
        for (Map.Entry<String, UUID> entry : properties.getCities().entrySet()) {
            if (entry.getValue().equals(cityId)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
