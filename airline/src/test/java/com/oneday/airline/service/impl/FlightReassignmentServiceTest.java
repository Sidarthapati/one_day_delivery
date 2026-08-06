package com.oneday.airline.service.impl;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.events.FlightEventProducer;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.service.AwbBookingService;
import com.oneday.common.kafka.enums.FlightReassignReason;
import com.oneday.common.kafka.events.flight.FlightReassignedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightReassignmentServiceTest {

    @Mock AwbRepository awbRepository;
    @Mock AwbBookingService awbBookingService;
    @Mock FlightSelectionService flightSelectionService;
    @Mock FlightEventProducer flightEventProducer;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T02:00:00Z"), ZoneOffset.UTC);
    private final UUID bagId = UUID.randomUUID();

    private FlightReassignmentService service() {
        return new FlightReassignmentService(awbRepository, awbBookingService, flightSelectionService,
                flightEventProducer, clock);
    }

    private Awb oldAwb() {
        Awb a = new Awb();
        a.setBagId(bagId);
        a.setFlightNo("ODDELBOM06");
        a.setOriginHub("DEL");
        a.setDestHub("BOM");
        a.setParcelCount(3);
        a.setTotalWeightGrams(45_000);
        a.setStatus(AwbStatus.BOOKED);
        return a;
    }

    @Test
    void supersedesTheOldAwbAndBooksAReplacementOnTheSelectedFlight() {
        Awb old = oldAwb();
        var selection = new FlightSelectionService.Selection("ODDELBOM12", LocalDate.of(2026, 7, 20), "DEL", "BOM",
                Instant.parse("2026-07-20T06:30:00Z"), Instant.parse("2026-07-20T08:30:00Z"),
                Instant.parse("2026-07-20T03:30:00Z"), 2000);
        when(flightSelectionService.select("DEL", "BOM", clock.instant())).thenReturn(selection);
        Awb replacement = new Awb();
        ReflectionTestUtils.setField(replacement, "id", UUID.randomUUID());   // id is @GeneratedValue, no setter
        when(awbBookingService.book(any(AwbBookingService.BookBagCommand.class))).thenReturn(replacement);
        when(awbRepository.save(any(Awb.class))).thenAnswer(inv -> inv.getArgument(0));

        Awb result = service().reassign(old, FlightReassignReason.CANCELLATION);

        assertThat(result).isSameAs(replacement);
        assertThat(old.getStatus()).isEqualTo(AwbStatus.SUPERSEDED);
        assertThat(old.getSupersededBy()).isEqualTo(replacement.getId());

        ArgumentCaptor<AwbBookingService.BookBagCommand> commandCaptor =
                ArgumentCaptor.forClass(AwbBookingService.BookBagCommand.class);
        verify(awbBookingService).book(commandCaptor.capture());
        assertThat(commandCaptor.getValue().bagId()).isEqualTo(bagId);
        assertThat(commandCaptor.getValue().flightNo()).isEqualTo("ODDELBOM12");
        assertThat(commandCaptor.getValue().parcelCount()).isEqualTo(3);
        assertThat(commandCaptor.getValue().weightGrams()).isEqualTo(45_000);

        ArgumentCaptor<FlightReassignedEvent> eventCaptor = ArgumentCaptor.forClass(FlightReassignedEvent.class);
        verify(flightEventProducer).emitReassigned(eventCaptor.capture());
        FlightReassignedEvent event = eventCaptor.getValue();
        assertThat(event.toFlightNo()).isEqualTo("ODDELBOM12");
        assertThat(event.fromFlightNo()).isEqualTo("ODDELBOM06");
        assertThat(event.destHub()).isEqualTo("BOM");
        assertThat(event.parcelIds()).isNull();   // whole-bag move
        assertThat(event.reason()).isEqualTo(FlightReassignReason.CANCELLATION);
    }
}
