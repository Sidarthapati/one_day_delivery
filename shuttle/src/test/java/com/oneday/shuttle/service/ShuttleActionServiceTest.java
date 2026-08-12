package com.oneday.shuttle.service;

import com.oneday.airline.service.AirlineCustodyService;
import com.oneday.airline.service.ShuttleInboundQueryService;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.service.FlightBagService;
import com.oneday.hub.service.FlightBagService.BagParcelInfo;
import com.oneday.hub.service.exception.IllegalBagStateException;
import com.oneday.shuttle.domain.ShuttleDirection;
import com.oneday.shuttle.domain.ShuttleLeg;
import com.oneday.shuttle.dto.BagActionResult;
import com.oneday.shuttle.repository.ShuttleLegRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShuttleActionServiceTest {

    @Mock FlightBagService flightBagService;
    @Mock AirlineCustodyService airlineCustodyService;
    @Mock ShuttleInboundQueryService inboundQuery;
    @Mock ShuttleLegRepository legRepository;
    @InjectMocks ShuttleActionService service;

    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void outToAirport_dispatchesSealedBag_andBindsEveryParcelOutbound() {
        UUID bagId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        when(flightBagService.bag(bagId)).thenReturn(FlightBag.builder().originHub("HYD").flightNo("6E6025").build());
        when(flightBagService.parcelsFor(bagId)).thenReturn(List.of(
                new BagParcelInfo(p1, "1DD-1", 900), new BagParcelInfo(p2, "1DD-2", 1100)));

        BagActionResult result = service.outToAirport(AGENT, "HYD", List.of(bagId));

        verify(flightBagService).dispatch(bagId);
        ArgumentCaptor<ShuttleLeg> legs = ArgumentCaptor.forClass(ShuttleLeg.class);
        verify(legRepository, org.mockito.Mockito.times(2)).save(legs.capture());
        assertThat(legs.getAllValues()).allSatisfy(l -> {
            assertThat(l.getAgentId()).isEqualTo(AGENT);
            assertThat(l.getDirection()).isEqualTo(ShuttleDirection.OUTBOUND);
            assertThat(l.getBagId()).isEqualTo(bagId);
        });
        assertThat(result.results()).singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo("DISPATCHED"));
    }

    @Test
    void outToAirport_skipsBagInAnotherCity_withoutDispatching() {
        UUID bagId = UUID.randomUUID();
        when(flightBagService.bag(bagId)).thenReturn(FlightBag.builder().originHub("DEL").build());

        BagActionResult result = service.outToAirport(AGENT, "HYD", List.of(bagId));

        verify(flightBagService, never()).dispatch(any());
        verify(legRepository, never()).save(any());
        assertThat(result.results()).singleElement()
                .satisfies(o -> assertThat(o.outcome()).startsWith("SKIPPED"));
    }

    @Test
    void outToAirport_skipsUnsealedBag_ratherThanFailingTheTrip() {
        UUID bagId = UUID.randomUUID();
        when(flightBagService.bag(bagId)).thenReturn(FlightBag.builder().originHub("HYD").build());
        when(flightBagService.parcelsFor(bagId)).thenReturn(List.of(new BagParcelInfo(UUID.randomUUID(), "1DD-1", 900)));
        org.mockito.Mockito.doThrow(new IllegalBagStateException("not SEALED"))
                .when(flightBagService).dispatch(bagId);

        BagActionResult result = service.outToAirport(AGENT, "HYD", List.of(bagId));

        verify(legRepository, never()).save(any());
        assertThat(result.results()).singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo("SKIPPED:NOT_READY"));
    }

    @Test
    void collectFromAirport_firesDestShuttleIn_andBindsEveryParcelInbound() {
        UUID awbId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        when(inboundQuery.parcelIdsForAwb(awbId)).thenReturn(List.of(p1));
        when(airlineCustodyService.record(awbId, ScanEventType.DEST_SHUTTLE_IN)).thenReturn(1);

        int scanned = service.collectFromAirport(AGENT, awbId);

        assertThat(scanned).isEqualTo(1);
        ArgumentCaptor<ShuttleLeg> leg = ArgumentCaptor.forClass(ShuttleLeg.class);
        verify(legRepository).save(leg.capture());
        assertThat(leg.getValue().getDirection()).isEqualTo(ShuttleDirection.INBOUND);
        assertThat(leg.getValue().getAwbId()).isEqualTo(awbId);
        assertThat(leg.getValue().getParcelId()).isEqualTo(p1);
    }

    @Test
    void requestSeal_delegatesToHub_whenBagIsInAgentCity() {
        UUID bagId = UUID.randomUUID();
        when(flightBagService.bag(bagId)).thenReturn(FlightBag.builder().originHub("HYD").build());

        service.requestSeal(AGENT, "HYD", bagId);

        verify(flightBagService).requestSeal(eq(bagId));
    }
}
