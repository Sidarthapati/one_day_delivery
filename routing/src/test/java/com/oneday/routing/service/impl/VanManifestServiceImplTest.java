package com.oneday.routing.service.impl;

import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.model.BindingResult;
import com.oneday.routing.service.port.HubSortPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VanManifestServiceImplTest {

    @Mock HubSortPort hubSortPort;
    @Mock com.oneday.routing.service.port.DaAccumulationPort daAccumulationPort;
    @Mock com.oneday.routing.service.port.FlightCutoffPort flightCutoffPort;
    @Mock RoutePlanRepository planRepository;
    @Mock RoutePlanStopRepository stopRepository;
    @Mock DaCronScheduleRepository cronRepository;
    @Mock CityFleetConfigRepository fleetConfigRepository;
    @Mock VanManifestRepository manifestRepository;
    @Mock VanManifestItemRepository itemRepository;
    @Mock GridDataAdapter gridDataAdapter;
    @Mock CronEventProducer cronEventProducer;

    private final RoutingProperties properties = new RoutingProperties(); // daDeliveryMinutes = 30

    private static final UUID CITY = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID VAN = UUID.randomUUID();
    private static final UUID VERTEX = UUID.randomUUID();
    private static final UUID DA = UUID.randomUUID();
    private static final UUID HEX = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 6, 18);

    private VanManifestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VanManifestServiceImpl(hubSortPort, daAccumulationPort, flightCutoffPort,
                planRepository, stopRepository, cronRepository, fleetConfigRepository,
                manifestRepository, itemRepository, gridDataAdapter, cronEventProducer, properties);

        RoutePlan plan = RoutePlan.builder().id(PLAN).cityId(CITY).validForDate(DATE)
                .status(RoutePlanStatus.APPROVED).revision(1).build();
        when(planRepository.findByCityIdAndValidForDateAndStatus(CITY, DATE, RoutePlanStatus.APPROVED))
                .thenReturn(List.of(plan));
        when(fleetConfigRepository.findByCityId(CITY))
                .thenReturn(Optional.of(CityFleetConfig.builder().cityId(CITY).capacityPackets(1).build()));
        when(gridDataAdapter.hexToDa(CITY, DATE)).thenReturn(Map.of(HEX, DA));
        when(cronRepository.findByRoutePlanId(PLAN)).thenReturn(List.of(
                DaCronSchedule.builder().routePlanId(PLAN).daId(DA).hexVertexId(VERTEX).vanId(VAN)
                        .meetingTimes("[]").cityId(CITY).validDate(DATE).build()));
        // Two loops at the same vertex: loop 0 arrives 07:30, loop 1 arrives 08:30.
        when(stopRepository.findByRoutePlanId(PLAN)).thenReturn(List.of(
                stop(0, 1, LocalTime.of(7, 30)),
                stop(1, 1, LocalTime.of(8, 30))));
    }

    // Manifests/items get an id on save so children can reference it. Only stubbed where a bind happens.
    private void stubManifestPersistence() {
        when(manifestRepository.findByVanIdAndLoopIndexAndValidDate(any(), anyInt(), eq(DATE)))
                .thenReturn(Optional.empty());
        when(manifestRepository.save(any(VanManifest.class))).thenAnswer(inv -> {
            VanManifest m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(itemRepository.save(any(VanManifestItem.class))).thenAnswer(inv -> {
            VanManifestItem i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });
    }

    @Test
    void deliveriesBindSlaFirst_tightParcelJumpsAheadOfSlackOne() {
        // Slack listed first, tight second; capacity is 1/loop. SLA-first must give loop 0 to the tight one.
        stubManifestPersistence();
        HubSortPort.ReadyForDeliveryParcel slack = parcel(LocalTime.of(9, 30)); // both loops feasible
        HubSortPort.ReadyForDeliveryParcel tight = parcel(LocalTime.of(8, 15)); // only loop 0 (08:00 ≤ 08:15)
        when(hubSortPort.readyForDelivery(CITY)).thenReturn(List.of(slack, tight));

        BindingResult result = service.bindDeliveries(CITY, DATE);

        assertThat(result.overflowed()).isEmpty();
        assertThat(result.bound()).hasSize(2);
        assertThat(loopOf(result, tight)).isEqualTo(0);
        assertThat(loopOf(result, slack)).isEqualTo(1);
        verify(cronEventProducer, never()).emitLoopOverflow(any(), any(), any(), anyInt(), any());
    }

    @Test
    void overflowEscalates_neverSilentlyDrops() {
        // Two tight parcels, both only feasible on loop 0, capacity 1 → second overflows, not dropped.
        stubManifestPersistence();
        HubSortPort.ReadyForDeliveryParcel tight1 = parcel(LocalTime.of(8, 15));
        HubSortPort.ReadyForDeliveryParcel tight2 = parcel(LocalTime.of(8, 15));
        when(hubSortPort.readyForDelivery(CITY)).thenReturn(List.of(tight1, tight2));

        BindingResult result = service.bindDeliveries(CITY, DATE);

        assertThat(result.bound()).hasSize(1);
        assertThat(result.overflowed()).hasSize(1);
        verify(cronEventProducer, times(1)).emitLoopOverflow(eq(CITY), eq(VAN), any(), eq(-1), any());
        verify(itemRepository, times(1)).save(any(VanManifestItem.class)); // only the bound one persisted
    }

    @Test
    void unresolvedHex_isReportedNotBoundNotOverflowed() {
        HubSortPort.ReadyForDeliveryParcel orphan = new HubSortPort.ReadyForDeliveryParcel(
                UUID.randomUUID(), UUID.randomUUID(), instant(LocalTime.of(7, 0)), instant(LocalTime.of(9, 0)));
        when(hubSortPort.readyForDelivery(CITY)).thenReturn(List.of(orphan));

        BindingResult result = service.bindDeliveries(CITY, DATE);

        assertThat(result.bound()).isEmpty();
        assertThat(result.overflowed()).isEmpty();
        assertThat(result.unresolved()).hasSize(1);
        verify(itemRepository, never()).save(any());
    }

    private int loopOf(BindingResult r, HubSortPort.ReadyForDeliveryParcel p) {
        return r.bound().stream().filter(b -> b.parcelId().equals(p.parcelId()))
                .findFirst().orElseThrow().loopIndex();
    }

    private HubSortPort.ReadyForDeliveryParcel parcel(LocalTime deadline) {
        return new HubSortPort.ReadyForDeliveryParcel(UUID.randomUUID(), HEX,
                instant(LocalTime.of(7, 0)), instant(deadline));
    }

    private RoutePlanStop stop(int loop, int seq, LocalTime arrival) {
        return RoutePlanStop.builder().routePlanId(PLAN).vanId(VAN).loopIndex(loop).stopSeq(seq)
                .nodeKind(StopNodeKind.MEETING_VERTEX).hexVertexId(VERTEX)
                .plannedArrival(arrival).plannedDeparture(arrival.plusMinutes(5)).build();
    }

    private static Instant instant(LocalTime time) {
        return LocalDateTime.of(DATE, time).atZone(ClockConfig.IST).toInstant();
    }
}
