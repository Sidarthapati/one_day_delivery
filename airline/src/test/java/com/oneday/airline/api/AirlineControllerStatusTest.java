package com.oneday.airline.api;

import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.dto.FlightStatusResponse;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.service.provider.FlightProviderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The GHA console's flight-status endpoint prefers our own flight_instance's real progress
 * (DEPARTED/LANDED) — the same signal the customer sees — over the consolidator's ON_TIME word.
 */
@ExtendWith(MockitoExtension.class)
class AirlineControllerStatusTest {

    @Mock FlightInstanceRepository flightInstanceRepository;
    @Mock FlightProviderPort flightProviderPort;

    private final LocalDate date = LocalDate.of(2026, 8, 12);
    private final Instant departure = Instant.parse("2026-08-12T18:16:00Z");
    private final Instant arrival = Instant.parse("2026-08-12T20:04:00Z");

    private AirlineController controller() {
        return new AirlineController(null, flightInstanceRepository, null, null, null, null, null, flightProviderPort);
    }

    private FlightInstance instance(FlightInstanceStatus status) {
        FlightInstance fi = new FlightInstance();
        fi.setFlightNo("6E6025");
        fi.setFlightDate(date);
        fi.setDeparture(departure);
        fi.setArrival(arrival);
        fi.setStatus(status);
        return fi;
    }

    @Test
    void landedInstance_reportsLandedFromOurSideWithoutAskingTheConsolidator() {
        when(flightInstanceRepository.findByFlightNoAndFlightDate("6E6025", date))
                .thenReturn(Optional.of(instance(FlightInstanceStatus.LANDED)));

        FlightStatusResponse res = controller().flightStatus("6E6025", date);

        assertThat(res.status()).isEqualTo("LANDED");
        assertThat(res.estimatedArrival()).isEqualTo(arrival);
        verifyNoInteractions(flightProviderPort);
    }

    @Test
    void scheduledInstance_fallsBackToTheConsolidatorForOnTimeDelayInfo() {
        when(flightInstanceRepository.findByFlightNoAndFlightDate("6E6025", date))
                .thenReturn(Optional.of(instance(FlightInstanceStatus.SCHEDULED)));
        when(flightProviderPort.status("6E6025", date)).thenReturn(
                new FlightProviderPort.FlightStatusResult(FlightProviderPort.FlightRealWorldStatus.ON_TIME, departure, arrival));

        FlightStatusResponse res = controller().flightStatus("6E6025", date);

        assertThat(res.status()).isEqualTo("ON_TIME");
        verify(flightProviderPort).status("6E6025", date);
    }

    @Test
    void noInstanceYet_fallsBackToTheConsolidator() {
        when(flightInstanceRepository.findByFlightNoAndFlightDate("6E6025", date)).thenReturn(Optional.empty());
        when(flightProviderPort.status("6E6025", date)).thenReturn(
                new FlightProviderPort.FlightStatusResult(FlightProviderPort.FlightRealWorldStatus.ON_TIME, departure, arrival));

        FlightStatusResponse res = controller().flightStatus("6E6025", date);

        assertThat(res.status()).isEqualTo("ON_TIME");
    }
}
