package com.oneday.airline.batch;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.events.FlightEventProducer;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.service.impl.FlightReassignmentService;
import com.oneday.airline.service.provider.FlightProviderPort;
import com.oneday.common.kafka.enums.FlightReassignReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightStatusPollJobTest {

    @Mock FlightInstanceRepository flightInstanceRepository;
    @Mock AwbRepository awbRepository;
    @Mock AwbParcelRepository awbParcelRepository;
    @Mock FlightProviderPort flightProviderPort;
    @Mock FlightEventProducer flightEventProducer;
    @Mock FlightReassignmentService flightReassignmentService;

    private final AirlineProperties properties = new AirlineProperties();   // delayReassignThresholdMinutes = 60
    private final Instant departure = Instant.parse("2026-07-20T06:30:00Z");
    private final Instant arrival = Instant.parse("2026-07-20T08:30:00Z");

    private FlightStatusPollJob job(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new FlightStatusPollJob(flightInstanceRepository, awbRepository, awbParcelRepository,
                flightProviderPort, flightEventProducer, flightReassignmentService, properties, clock);
    }

    private FlightInstance instance(FlightInstanceStatus status) {
        FlightInstance fi = new FlightInstance();
        fi.setFlightNo("ODDELBOM06");
        fi.setFlightDate(LocalDate.of(2026, 7, 20));
        fi.setOriginHub("DEL");
        fi.setDestHub("BOM");
        fi.setDeparture(departure);
        fi.setArrival(arrival);
        fi.setStatus(status);
        return fi;
    }

    private Awb bookedAwb() {
        Awb a = new Awb();
        a.setStatus(AwbStatus.BOOKED);
        return a;
    }

    @Test
    void departureTimePassed_flipsToDepartedAndNotifiesEveryParcel() {
        FlightInstance fi = instance(FlightInstanceStatus.SCHEDULED);
        Awb awb = bookedAwb();
        UUID parcelId = UUID.randomUUID();
        AwbParcel parcel = new AwbParcel();
        parcel.setParcelId(parcelId);
        when(awbRepository.findByFlightNoAndFlightDate("ODDELBOM06", fi.getFlightDate())).thenReturn(List.of(awb));
        when(awbParcelRepository.findByAwbId(any())).thenReturn(List.of(parcel));
        when(flightProviderPort.status("ODDELBOM06", fi.getFlightDate())).thenReturn(
                new FlightProviderPort.FlightStatusResult(FlightProviderPort.FlightRealWorldStatus.ON_TIME,
                        departure, arrival));

        job(departure.plusSeconds(60)).processInstance(fi);

        assertThat(fi.getStatus()).isEqualTo(FlightInstanceStatus.DEPARTED);
        verify(flightInstanceRepository).save(fi);
        verify(flightEventProducer).emitDeparted(parcelId);
        verify(flightEventProducer, never()).emitLanded(any());
    }

    @Test
    void arrivalTimePassed_flipsToLandedAndSkipsProviderPoll() {
        FlightInstance fi = instance(FlightInstanceStatus.DEPARTED);
        Awb awb = bookedAwb();
        UUID parcelId = UUID.randomUUID();
        AwbParcel parcel = new AwbParcel();
        parcel.setParcelId(parcelId);
        when(awbRepository.findByFlightNoAndFlightDate("ODDELBOM06", fi.getFlightDate())).thenReturn(List.of(awb));
        when(awbParcelRepository.findByAwbId(any())).thenReturn(List.of(parcel));

        job(arrival.plusSeconds(60)).processInstance(fi);

        assertThat(fi.getStatus()).isEqualTo(FlightInstanceStatus.LANDED);
        verify(flightEventProducer).emitLanded(parcelId);
        verifyNoInteractions(flightProviderPort);   // landed — no need to ask the vendor anything more
    }

    @Test
    void providerReportsCancelled_reassignsEveryBookedAwbAndMarksTheInstanceCancelled() {
        FlightInstance fi = instance(FlightInstanceStatus.SCHEDULED);
        Awb awb = bookedAwb();
        when(awbRepository.findByFlightNoAndFlightDate("ODDELBOM06", fi.getFlightDate())).thenReturn(List.of(awb));
        when(flightProviderPort.status("ODDELBOM06", fi.getFlightDate())).thenReturn(
                new FlightProviderPort.FlightStatusResult(FlightProviderPort.FlightRealWorldStatus.CANCELLED,
                        departure, arrival));

        // Ready well before departure so the SCHEDULED→DEPARTED time-transition doesn't also fire.
        job(departure.minusSeconds(3600)).processInstance(fi);

        verify(flightReassignmentService).reassign(awb, FlightReassignReason.CANCELLATION);
        assertThat(fi.getStatus()).isEqualTo(FlightInstanceStatus.CANCELLED);
    }

    @Test
    void providerReportsALongDelay_reassignsRatherThanJustNotifying() {
        FlightInstance fi = instance(FlightInstanceStatus.SCHEDULED);
        Awb awb = bookedAwb();
        when(awbRepository.findByFlightNoAndFlightDate("ODDELBOM06", fi.getFlightDate())).thenReturn(List.of(awb));
        Instant delayedDeparture = departure.plusSeconds(90 * 60);   // 90 min > 60 min threshold
        when(flightProviderPort.status("ODDELBOM06", fi.getFlightDate())).thenReturn(
                new FlightProviderPort.FlightStatusResult(FlightProviderPort.FlightRealWorldStatus.DELAYED,
                        delayedDeparture, arrival.plusSeconds(90 * 60)));

        job(departure.minusSeconds(3600)).processInstance(fi);

        verify(flightReassignmentService).reassign(awb, FlightReassignReason.DELAY);
        verify(flightEventProducer, never()).emitTimeChanged(any());
    }

    @Test
    void providerReportsAShortDelay_onlyEmitsAnAdvisoryTimeChange() {
        FlightInstance fi = instance(FlightInstanceStatus.SCHEDULED);
        Instant delayedDeparture = departure.plusSeconds(20 * 60);   // 20 min < 60 min threshold
        Instant delayedArrival = arrival.plusSeconds(20 * 60);
        when(flightProviderPort.status("ODDELBOM06", fi.getFlightDate())).thenReturn(
                new FlightProviderPort.FlightStatusResult(FlightProviderPort.FlightRealWorldStatus.DELAYED,
                        delayedDeparture, delayedArrival));

        job(departure.minusSeconds(3600)).processInstance(fi);

        verifyNoInteractions(flightReassignmentService);
        assertThat(fi.getDeparture()).isEqualTo(delayedDeparture);
        assertThat(fi.getArrival()).isEqualTo(delayedArrival);

        ArgumentCaptor<com.oneday.common.kafka.events.flight.FlightTimeChangedEvent> captor =
                ArgumentCaptor.forClass(com.oneday.common.kafka.events.flight.FlightTimeChangedEvent.class);
        verify(flightEventProducer).emitTimeChanged(captor.capture());
        assertThat(captor.getValue().newDeparture()).isEqualTo(delayedDeparture);
    }
}
