package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.domain.FlightSchedule;
import com.oneday.airline.domain.LaneRateCard;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.repository.FlightScheduleRepository;
import com.oneday.airline.repository.LaneRateCardRepository;
import com.oneday.airline.service.AwbBookingService;
import com.oneday.airline.service.exception.FlightScheduleNotFoundException;
import com.oneday.airline.service.provider.FlightProviderPort;
import com.oneday.hub.service.FlightBagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwbBookingServiceImplTest {

    @Mock AwbRepository awbRepository;
    @Mock AwbParcelRepository awbParcelRepository;
    @Mock FlightInstanceRepository flightInstanceRepository;
    @Mock FlightScheduleRepository flightScheduleRepository;
    @Mock LaneRateCardRepository laneRateCardRepository;
    @Mock FlightProviderPort flightProviderPort;
    @Mock FlightBagService flightBagService;

    private final AirlineProperties properties = new AirlineProperties();
    private final CostEstimator costEstimator = new CostEstimator(properties);

    private AwbBookingServiceImpl service() {
        return new AwbBookingServiceImpl(awbRepository, awbParcelRepository, flightInstanceRepository,
                flightScheduleRepository, laneRateCardRepository, flightProviderPort, flightBagService,
                costEstimator, properties);
    }

    private final UUID bagId = UUID.randomUUID();
    private final LocalDate flightDate = LocalDate.of(2026, 7, 20);

    private FlightSchedule schedule() {
        FlightSchedule s = new FlightSchedule();
        s.setOriginHub("DEL");
        s.setDestHub("BOM");
        s.setCarrier("SIM-CARRIER");
        s.setFlightNo("ODDELBOM12");
        s.setDepartureTime(LocalTime.of(12, 0));
        s.setArrivalTime(LocalTime.of(14, 0));
        s.setCapacityKg(2000);
        s.setActive(true);
        return s;
    }

    private LaneRateCard rateCard() {
        LaneRateCard c = new LaneRateCard();
        c.setMinChargePaise(150_000);
        c.setTerminalHandlingPaise(38_000);
        c.setRateBelow45kgPaisePerKg(6_500);
        c.setRateQ45PaisePerKg(5_800);
        c.setRateQ100PaisePerKg(5_200);
        c.setRateQ300PaisePerKg(4_700);
        c.setRateQ500PaisePerKg(4_300);
        c.setRateQ1000PaisePerKg(4_000);
        return c;
    }

    @Test
    void firstBookingForAFlight_createsTheFlightInstanceAndBooksIt() {
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.empty());
        when(flightInstanceRepository.findByFlightNoAndFlightDateForUpdate("ODDELBOM12", flightDate))
                .thenReturn(Optional.empty());
        when(flightScheduleRepository.findByFlightNo("ODDELBOM12")).thenReturn(Optional.of(schedule()));
        when(flightInstanceRepository.save(any(FlightInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(laneRateCardRepository.findByOriginHubAndDestHubAndStatus("DEL", "BOM", "ACTIVE"))
                .thenReturn(Optional.of(rateCard()));
        when(flightProviderPort.book(anyString(), any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new FlightProviderPort.BookingConfirmation("SIM-REF-123"));
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID parcelA = UUID.randomUUID();
        UUID parcelB = UUID.randomUUID();
        when(flightBagService.parcelsFor(bagId)).thenReturn(List.of(
                new FlightBagService.BagParcelInfo(parcelA, "REF-A", 30_000),
                new FlightBagService.BagParcelInfo(parcelB, "REF-B", 15_000)));
        when(awbParcelRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Awb result = service().book(new AwbBookingService.BookBagCommand(bagId, "ODDELBOM12", flightDate, 12, 45_000));

        assertThat(result.getBagId()).isEqualTo(bagId);
        assertThat(result.getFlightNo()).isEqualTo("ODDELBOM12");
        assertThat(result.getOriginHub()).isEqualTo("DEL");
        assertThat(result.getDestHub()).isEqualTo("BOM");
        assertThat(result.getTotalWeightGrams()).isEqualTo(45_000);
        assertThat(result.getParcelCount()).isEqualTo(12);
        assertThat(result.getProviderRef()).isEqualTo("SIM-REF-123");
        assertThat(result.getStatus()).isEqualTo(AwbStatus.BOOKED);

        ArgumentCaptor<FlightInstance> instanceCaptor = ArgumentCaptor.forClass(FlightInstance.class);
        verify(flightInstanceRepository).save(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getBookedWeightGrams()).isEqualTo(45_000);
        assertThat(instanceCaptor.getValue().getStatus()).isEqualTo(FlightInstanceStatus.SCHEDULED);

        // 45kg @ Q45 (5800/kg) + 38000 handling = 299000. Split 30kg/15kg: 199333.33/99666.67 →
        // floors 199333/99666 sum to 298999, the 1-paise remainder goes to the heavier (30kg) line.
        ArgumentCaptor<List<AwbParcel>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(awbParcelRepository).saveAll(linesCaptor.capture());
        List<AwbParcel> lines = linesCaptor.getValue();
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().mapToLong(AwbParcel::getAllocatedCostPaise).sum()).isEqualTo(299_000);
        AwbParcel lineA = lines.stream().filter(l -> l.getParcelId().equals(parcelA)).findFirst().orElseThrow();
        AwbParcel lineB = lines.stream().filter(l -> l.getParcelId().equals(parcelB)).findFirst().orElseThrow();
        assertThat(lineA.getAllocatedCostPaise()).isEqualTo(199_334);
        assertThat(lineB.getAllocatedCostPaise()).isEqualTo(99_666);
        assertThat(lineA.getShipmentRef()).isEqualTo("REF-A");
        assertThat(lineA.getAwbId()).isEqualTo(result.getId());
    }

    @Test
    void noParcelsFoundForTheBag_skipsParcelLinesWithoutFailingTheBooking() {
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.empty());
        when(flightInstanceRepository.findByFlightNoAndFlightDateForUpdate("ODDELBOM12", flightDate))
                .thenReturn(Optional.empty());
        when(flightScheduleRepository.findByFlightNo("ODDELBOM12")).thenReturn(Optional.of(schedule()));
        when(flightInstanceRepository.save(any(FlightInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(laneRateCardRepository.findByOriginHubAndDestHubAndStatus("DEL", "BOM", "ACTIVE"))
                .thenReturn(Optional.of(rateCard()));
        when(flightProviderPort.book(anyString(), any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new FlightProviderPort.BookingConfirmation("SIM-REF-789"));
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));
        when(flightBagService.parcelsFor(bagId)).thenReturn(List.of());

        Awb result = service().book(new AwbBookingService.BookBagCommand(bagId, "ODDELBOM12", flightDate, 12, 45_000));

        assertThat(result.getStatus()).isEqualTo(AwbStatus.BOOKED);
        verify(awbParcelRepository, never()).saveAll(any());
    }

    @Test
    void secondBagOnAnExistingFlight_incrementsTheRunningWeightRatherThanResettingIt() {
        FlightInstance existing = new FlightInstance();
        existing.setFlightNo("ODDELBOM12");
        existing.setFlightDate(flightDate);
        existing.setOriginHub("DEL");
        existing.setDestHub("BOM");
        existing.setCapacityKg(2000);
        existing.setBookedWeightGrams(45_000);   // an earlier bag already booked 45kg
        existing.setDeparture(Instant.parse("2026-07-20T06:30:00Z"));
        existing.setStatus(FlightInstanceStatus.SCHEDULED);

        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.empty());
        when(flightInstanceRepository.findByFlightNoAndFlightDateForUpdate("ODDELBOM12", flightDate))
                .thenReturn(Optional.of(existing));
        when(flightInstanceRepository.save(any(FlightInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(laneRateCardRepository.findByOriginHubAndDestHubAndStatus("DEL", "BOM", "ACTIVE"))
                .thenReturn(Optional.of(rateCard()));
        when(flightProviderPort.book(anyString(), any(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new FlightProviderPort.BookingConfirmation("SIM-REF-456"));
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));

        service().book(new AwbBookingService.BookBagCommand(bagId, "ODDELBOM12", flightDate, 8, 20_000));

        verify(flightScheduleRepository, never()).findByFlightNo(any());   // no new instance created
        assertThat(existing.getBookedWeightGrams()).isEqualTo(65_000);     // 45kg + 20kg
    }

    @Test
    void redeliveredBagSealedNotification_returnsTheExistingBookingWithoutBookingAgain() {
        Awb alreadyBooked = new Awb();
        alreadyBooked.setBagId(bagId);
        alreadyBooked.setAwbNo("AWB-EXISTING");
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.of(alreadyBooked));

        Awb result = service().book(new AwbBookingService.BookBagCommand(bagId, "ODDELBOM12", flightDate, 12, 45_000));

        assertThat(result).isSameAs(alreadyBooked);
        verifyNoInteractions(flightProviderPort, flightInstanceRepository, flightScheduleRepository,
                flightBagService, awbParcelRepository);
    }

    @Test
    void unknownFlightNumber_throws() {
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.empty());
        when(flightInstanceRepository.findByFlightNoAndFlightDateForUpdate("GHOST99", flightDate))
                .thenReturn(Optional.empty());
        when(flightScheduleRepository.findByFlightNo("GHOST99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().book(
                new AwbBookingService.BookBagCommand(bagId, "GHOST99", flightDate, 1, 1000)))
                .isInstanceOf(FlightScheduleNotFoundException.class);
    }
}
