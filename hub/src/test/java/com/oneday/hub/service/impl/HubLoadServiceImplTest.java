package com.oneday.hub.service.impl;

import com.oneday.hub.config.HubProperties;
import com.oneday.hub.domain.DeliveryBagStatus;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.domain.HubLoadSnapshot;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.DeliveryBagRepository;
import com.oneday.hub.repository.FlightBagRepository;
import com.oneday.hub.repository.HubLoadSnapshotRepository;
import com.oneday.hub.repository.InboundReceiptRepository;
import com.oneday.hub.repository.StandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HubLoadServiceImplTest {

    @Mock StandRepository standRepository;
    @Mock FlightBagRepository flightBagRepository;
    @Mock DeliveryBagRepository deliveryBagRepository;
    @Mock InboundReceiptRepository inboundReceiptRepository;
    @Mock HubLoadSnapshotRepository snapshotRepository;
    @Mock HubEventProducer eventProducer;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-03T06:00:00Z"), ZoneOffset.UTC);
    private final UUID hubId = UUID.randomUUID();

    private HubLoadServiceImpl service() {
        return new HubLoadServiceImpl(standRepository, flightBagRepository, deliveryBagRepository,
                inboundReceiptRepository, snapshotRepository, eventProducer, new HubProperties(), clock);
    }

    private void commonStubs(long openFlightBags) {
        when(standRepository.countByHubId(hubId)).thenReturn(10L);
        when(flightBagRepository.countByHubIdAndStatus(hubId, FlightBagStatus.OPEN)).thenReturn(openFlightBags);
        when(deliveryBagRepository.countByHubIdAndStatus(hubId, DeliveryBagStatus.OPEN)).thenReturn(0L);
        when(inboundReceiptRepository.countByHubIdAndReceivedAtAfter(eq(hubId), any())).thenReturn(20L);
        when(flightBagRepository.sumOpenParcelCount(hubId)).thenReturn(15L);
        when(deliveryBagRepository.sumOpenParcelCount(hubId)).thenReturn(0L);
        when(snapshotRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void overHighWater_persistsOverloaded_andEmitsAlert() {
        commonStubs(9);   // 9/10 stands occupied = 90% == default high-water

        HubLoadSnapshot snapshot = service().snapshot(hubId);

        assertThat(snapshot.getStandOccupancyPct()).isEqualTo(90);
        assertThat(snapshot.isOverloaded()).isTrue();
        assertThat(snapshot.getAwaitingSort()).isEqualTo(5);   // 20 arrivals - 15 bagged
        verify(eventProducer).emitHubOverloadAlert(snapshot);
    }

    @Test
    void belowHighWater_noAlert() {
        commonStubs(5);   // 50%

        HubLoadSnapshot snapshot = service().snapshot(hubId);

        assertThat(snapshot.getStandOccupancyPct()).isEqualTo(50);
        assertThat(snapshot.isOverloaded()).isFalse();
        verify(eventProducer, never()).emitHubOverloadAlert(any());
    }
}
