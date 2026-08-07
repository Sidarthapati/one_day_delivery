package com.oneday.airline.service.impl;

import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.consolidator.ConsolidatorFlightLeg;
import com.oneday.airline.consolidator.ConsolidatorFlightRepository;
import com.oneday.airline.consolidator.ConsolidatorLaneRate;
import com.oneday.airline.consolidator.ConsolidatorRateRepository;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.domain.FlightInstanceStatus;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.service.AwbBookingService;
import com.oneday.airline.service.exception.ConsolidatorLegNotFoundException;
import com.oneday.hub.service.FlightBagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwbBookingServiceImplTest {

    @Mock AwbRepository awbRepository;
    @Mock AwbParcelRepository awbParcelRepository;
    @Mock FlightInstanceRepository flightInstanceRepository;
    @Mock ConsolidatorFlightRepository consolidatorFlightRepository;
    @Mock ConsolidatorRateRepository consolidatorRateRepository;
    @Mock FlightBagService flightBagService;

    private final AirlineProperties properties = new AirlineProperties();
    private final CostEstimator costEstimator = new CostEstimator(properties);

    private AwbBookingServiceImpl service() {
        return new AwbBookingServiceImpl(awbRepository, awbParcelRepository, flightInstanceRepository,
                consolidatorFlightRepository, consolidatorRateRepository, flightBagService,
                costEstimator, properties);
    }

    private final UUID bagId = UUID.randomUUID();
    private final LocalDate flightDate = LocalDate.of(2026, 7, 20);

    private ConsolidatorFlightLeg leg() {
        return new ConsolidatorFlightLeg("ODDELBOM12", "SIM-CONSOLIDATOR", "DEL", "BOM", flightDate,
                Instant.parse("2026-07-20T06:30:00Z"), Instant.parse("2026-07-20T08:30:00Z"),
                2000, "SCHEDULED", null, null);
    }

    private ConsolidatorLaneRate rateCard() {
        return new ConsolidatorLaneRate("DEL", "BOM",
                150_000, 38_000,
                6_500, 5_800, 5_200, 4_700, 4_300, 4_000);
    }

    @Test
    void firstBookingForAFlight_createsTheFlightInstanceAndBooksIt() {
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.empty());
        when(flightInstanceRepository.findByFlightNoAndFlightDateForUpdate("ODDELBOM12", flightDate))
                .thenReturn(Optional.empty());
        when(consolidatorFlightRepository.findLeg("ODDELBOM12", flightDate)).thenReturn(leg());
        when(flightInstanceRepository.save(any(FlightInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consolidatorRateRepository.findActiveRate("DEL", "BOM")).thenReturn(rateCard());
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
        // No real vendor booking exists anymore (read-only consolidator access) — providerRef is a
        // purely local placeholder, never a vendor confirmation.
        assertThat(result.getProviderRef()).isEqualTo("NO-VENDOR-" + bagId.toString().substring(0, 8));
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
        when(consolidatorFlightRepository.findLeg("ODDELBOM12", flightDate)).thenReturn(leg());
        when(flightInstanceRepository.save(any(FlightInstance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consolidatorRateRepository.findActiveRate("DEL", "BOM")).thenReturn(rateCard());
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
        when(consolidatorRateRepository.findActiveRate("DEL", "BOM")).thenReturn(rateCard());
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));

        service().book(new AwbBookingService.BookBagCommand(bagId, "ODDELBOM12", flightDate, 8, 20_000));

        verify(consolidatorFlightRepository, never()).findLeg(any(), any());   // no new instance created
        assertThat(existing.getBookedWeightGrams()).isEqualTo(65_000);        // 45kg + 20kg
    }

    @Test
    void redeliveredBagSealedNotification_returnsTheExistingBookingWithoutBookingAgain() {
        Awb alreadyBooked = new Awb();
        alreadyBooked.setBagId(bagId);
        alreadyBooked.setAwbNo("AWB-EXISTING");
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.of(alreadyBooked));

        Awb result = service().book(new AwbBookingService.BookBagCommand(bagId, "ODDELBOM12", flightDate, 12, 45_000));

        assertThat(result).isSameAs(alreadyBooked);
        verifyNoInteractions(flightInstanceRepository, consolidatorFlightRepository, consolidatorRateRepository,
                flightBagService, awbParcelRepository);
    }

    @Test
    void unknownFlightNumber_throws() {
        when(awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)).thenReturn(Optional.empty());
        when(flightInstanceRepository.findByFlightNoAndFlightDateForUpdate("GHOST99", flightDate))
                .thenReturn(Optional.empty());
        when(consolidatorFlightRepository.findLeg("GHOST99", flightDate))
                .thenThrow(new ConsolidatorLegNotFoundException("GHOST99", flightDate));

        assertThatThrownBy(() -> service().book(
                new AwbBookingService.BookBagCommand(bagId, "GHOST99", flightDate, 1, 1000)))
                .isInstanceOf(ConsolidatorLegNotFoundException.class);
    }
}
