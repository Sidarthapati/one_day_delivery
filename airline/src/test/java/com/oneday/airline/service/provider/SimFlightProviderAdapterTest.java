package com.oneday.airline.service.provider;

import com.oneday.airline.domain.FlightSchedule;
import com.oneday.airline.repository.FlightScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimFlightProviderAdapterTest {

    @Mock FlightScheduleRepository flightScheduleRepository;

    private SimFlightProviderAdapter adapter;
    private final LocalDate flightDate = LocalDate.of(2026, 7, 20);

    private FlightSchedule schedule(String flightNo) {
        FlightSchedule s = new FlightSchedule();
        s.setFlightNo(flightNo);
        s.setOriginHub("DEL");
        s.setDestHub("BOM");
        s.setCarrier("SIM-CARRIER");
        s.setDepartureTime(LocalTime.of(12, 0));
        s.setArrivalTime(LocalTime.of(14, 0));
        s.setCapacityKg(2000);
        return s;
    }

    @Test
    void repeatedPollsOfTheSameFlightAgree() {
        adapter = new SimFlightProviderAdapter(flightScheduleRepository);
        when(flightScheduleRepository.findByFlightNo("ODDELBOM12")).thenReturn(Optional.of(schedule("ODDELBOM12")));

        FlightProviderPort.FlightStatusResult first = adapter.status("ODDELBOM12", flightDate);
        FlightProviderPort.FlightStatusResult second = adapter.status("ODDELBOM12", flightDate);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void mostlyOnTimeWithASmallDeterministicSliceOfDisruptions() {
        adapter = new SimFlightProviderAdapter(flightScheduleRepository);
        Map<FlightProviderPort.FlightRealWorldStatus, Integer> counts = new HashMap<>();

        // 500 distinct synthetic flight numbers on the same date — enough samples that, at the
        // adapter's ~3% CANCELLED / ~7% DELAYED injection rate, seeing zero of either is negligible.
        for (int i = 0; i < 500; i++) {
            String flightNo = "TESTFLIGHT" + i;
            when(flightScheduleRepository.findByFlightNo(flightNo)).thenReturn(Optional.of(schedule(flightNo)));
            FlightProviderPort.FlightStatusResult result = adapter.status(flightNo, flightDate);
            counts.merge(result.status(), 1, Integer::sum);
        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo(500);
        assertThat(counts.getOrDefault(FlightProviderPort.FlightRealWorldStatus.ON_TIME, 0))
                .as("ON_TIME should dominate").isGreaterThan(250);
        assertThat(counts.getOrDefault(FlightProviderPort.FlightRealWorldStatus.CANCELLED, 0))
                .as("a nonzero CANCELLED slice should appear").isGreaterThan(0);
        assertThat(counts.getOrDefault(FlightProviderPort.FlightRealWorldStatus.DELAYED, 0))
                .as("a nonzero DELAYED slice should appear").isGreaterThan(0);
    }

    @Test
    void delayedResult_pushesBothEstimatedTimesLaterByTheSameOffset() {
        adapter = new SimFlightProviderAdapter(flightScheduleRepository);
        // Search for a flight number in this fixed set that lands in the DELAYED bucket; the adapter's
        // determinism guarantees whichever one does, always will.
        FlightProviderPort.FlightStatusResult delayed = null;
        FlightSchedule base = schedule("PLACEHOLDER");
        for (int i = 0; i < 500 && delayed == null; i++) {
            String flightNo = "DELAYSEARCH" + i;
            FlightSchedule s = schedule(flightNo);
            when(flightScheduleRepository.findByFlightNo(flightNo)).thenReturn(Optional.of(s));
            FlightProviderPort.FlightStatusResult result = adapter.status(flightNo, flightDate);
            if (result.status() == FlightProviderPort.FlightRealWorldStatus.DELAYED) {
                delayed = result;
                base = s;
            }
        }

        assertThat(delayed).as("expected at least one DELAYED result in the search set").isNotNull();
        var scheduledDeparture = flightDate.atTime(base.getDepartureTime())
                .atZone(com.oneday.airline.config.ClockConfig.IST).toInstant();
        var scheduledArrival = flightDate.atTime(base.getArrivalTime())
                .atZone(com.oneday.airline.config.ClockConfig.IST).toInstant();
        assertThat(delayed.estimatedDeparture()).isAfter(scheduledDeparture);
        assertThat(delayed.estimatedArrival()).isAfter(scheduledArrival);
    }
}
