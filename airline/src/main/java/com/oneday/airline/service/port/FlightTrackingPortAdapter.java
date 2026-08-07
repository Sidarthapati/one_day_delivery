package com.oneday.airline.service.port;

import com.oneday.airline.config.HubCoordinates;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.orders.service.port.FlightTrackingPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The real M9 answer to orders' "where is this shipment's flight right now?" (§8), replacing {@code
 * NoOpFlightTrackingPort}. A parcel's position is a pure function of its current flight's departure/
 * arrival Instants plus the clock — nothing is stored, so this is always exactly up to date and
 * never goes stale.
 */
@Component
@Primary
class FlightTrackingPortAdapter implements FlightTrackingPort {

    private final AwbParcelRepository awbParcelRepository;
    private final AwbRepository awbRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final Clock clock;

    FlightTrackingPortAdapter(AwbParcelRepository awbParcelRepository, AwbRepository awbRepository,
                               FlightInstanceRepository flightInstanceRepository, Clock clock) {
        this.awbParcelRepository = awbParcelRepository;
        this.awbRepository = awbRepository;
        this.flightInstanceRepository = flightInstanceRepository;
        this.clock = clock;
    }

    @Override
    public Optional<LivePosition> currentPosition(UUID shipmentId) {
        Optional<AwbParcel> parcel = awbParcelRepository.findFirstByParcelIdOrderByCreatedAtDesc(shipmentId);
        if (parcel.isEmpty()) {
            return Optional.empty();
        }
        Optional<Awb> awb = awbRepository.findById(parcel.get().getAwbId());
        if (awb.isEmpty()) {
            return Optional.empty();
        }
        Optional<FlightInstance> instance = flightInstanceRepository
                .findByFlightNoAndFlightDate(awb.get().getFlightNo(), awb.get().getFlightDate());
        if (instance.isEmpty()) {
            return Optional.empty();
        }

        FlightInstance fi = instance.get();
        Instant now = clock.instant();
        if (now.isBefore(fi.getDeparture()) || !now.isBefore(fi.getArrival())) {
            return Optional.empty();   // not airborne yet, or already landed
        }

        double totalMillis = fi.getArrival().toEpochMilli() - fi.getDeparture().toEpochMilli();
        double elapsedMillis = now.toEpochMilli() - fi.getDeparture().toEpochMilli();
        double fraction = totalMillis > 0 ? clamp(elapsedMillis / totalMillis, 0, 1) : 0;

        HubCoordinates.Coord origin = HubCoordinates.of(fi.getOriginHub());
        HubCoordinates.Coord dest = HubCoordinates.of(fi.getDestHub());
        double lat = origin.lat() + fraction * (dest.lat() - origin.lat());
        double lon = origin.lon() + fraction * (dest.lon() - origin.lon());

        return Optional.of(new LivePosition(lat, lon, now, "IN_TRANSIT"));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
