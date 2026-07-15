package com.oneday.airline.service.port;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightTrackingPortAdapterTest {

    @Mock AwbParcelRepository awbParcelRepository;
    @Mock AwbRepository awbRepository;
    @Mock FlightInstanceRepository flightInstanceRepository;

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID awbId = UUID.randomUUID();
    private final Instant departure = Instant.parse("2026-07-20T06:30:00Z");
    private final Instant arrival = Instant.parse("2026-07-20T08:30:00Z");   // 2h flight, DEL→BOM

    private FlightTrackingPortAdapter adapter(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new FlightTrackingPortAdapter(awbParcelRepository, awbRepository, flightInstanceRepository, clock);
    }

    private void stubChain() {
        AwbParcel parcel = new AwbParcel();
        parcel.setParcelId(shipmentId);
        parcel.setAwbId(awbId);
        when(awbParcelRepository.findFirstByParcelIdOrderByCreatedAtDesc(shipmentId)).thenReturn(Optional.of(parcel));

        Awb awb = new Awb();
        awb.setFlightNo("ODDELBOM06");
        awb.setFlightDate(LocalDate.of(2026, 7, 20));
        when(awbRepository.findById(awbId)).thenReturn(Optional.of(awb));

        FlightInstance instance = new FlightInstance();
        instance.setFlightNo("ODDELBOM06");
        instance.setFlightDate(LocalDate.of(2026, 7, 20));
        instance.setOriginHub("DEL");
        instance.setDestHub("BOM");
        instance.setDeparture(departure);
        instance.setArrival(arrival);
        instance.setStatus(FlightInstanceStatus.DEPARTED);
        when(flightInstanceRepository.findByFlightNoAndFlightDate("ODDELBOM06", LocalDate.of(2026, 7, 20)))
                .thenReturn(Optional.of(instance));
    }

    @Test
    void beforeDeparture_returnsEmpty() {
        stubChain();
        var result = adapter(departure.minusSeconds(3600)).currentPosition(shipmentId);
        assertThat(result).isEmpty();
    }

    @Test
    void afterArrival_returnsEmpty() {
        stubChain();
        var result = adapter(arrival.plusSeconds(3600)).currentPosition(shipmentId);
        assertThat(result).isEmpty();
    }

    @Test
    void midFlight_interpolatesBetweenOriginAndDestination() {
        stubChain();
        // Exactly halfway between departure (06:30) and arrival (08:30) → 07:30.
        var result = adapter(departure.plusSeconds(3600)).currentPosition(shipmentId);

        assertThat(result).isPresent();
        var position = result.get();
        assertThat(position.status()).isEqualTo("IN_TRANSIT");
        // DEL (28.6139, 77.2090) → BOM (19.0760, 72.8777); midpoint should sit between the two.
        assertThat(position.lat()).isBetween(19.0760, 28.6139);
        assertThat(position.lon()).isBetween(72.8777, 77.2090);
        assertThat(position.lat()).isCloseTo((28.6139 + 19.0760) / 2, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void noAwbParcelForShipment_returnsEmpty() {
        when(awbParcelRepository.findFirstByParcelIdOrderByCreatedAtDesc(shipmentId)).thenReturn(Optional.empty());
        var result = adapter(departure.plusSeconds(3600)).currentPosition(shipmentId);
        assertThat(result).isEmpty();
    }
}
