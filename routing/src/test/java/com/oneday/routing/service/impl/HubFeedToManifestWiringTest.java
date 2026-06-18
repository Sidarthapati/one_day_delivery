package com.oneday.routing.service.impl;

import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.domain.InboundParcel;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.events.HubFeedConsumer;
import com.oneday.routing.events.payload.ParcelSortedForDeliveryEvent;
import com.oneday.routing.repository.CityFleetConfigRepository;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.InboundParcelRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.port.DaAccumulationPort;
import com.oneday.routing.service.port.FlightCutoffPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// End-to-end without a broker: an M7 event → HubFeedConsumer → buffer record + immediate bind →
// a persisted van_manifest_item, in one hop.
class HubFeedToManifestWiringTest {

    private static final UUID CITY = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID VAN = UUID.randomUUID();
    private static final UUID VERTEX = UUID.randomUUID();
    private static final UUID DA = UUID.randomUUID();
    private static final UUID HEX = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 6, 18);

    @Test
    void publishedSortEvent_immediatelyBecomesAManifestItem() {
        List<InboundParcel> buffer = new ArrayList<>();
        InboundParcelRepository inboundRepo = mock(InboundParcelRepository.class);
        when(inboundRepo.existsByKindAndParcelId(any(), any())).thenAnswer(inv ->
                buffer.stream().anyMatch(p -> p.getKind() == inv.getArgument(0) && p.getParcelId().equals(inv.getArgument(1))));
        when(inboundRepo.save(any(InboundParcel.class))).thenAnswer(inv -> {
            buffer.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        Map<String, VanManifest> manifestStore = new HashMap<>();
        List<VanManifestItem> itemStore = new ArrayList<>();

        RoutePlanRepository planRepo = mock(RoutePlanRepository.class);
        RoutePlanStopRepository stopRepo = mock(RoutePlanStopRepository.class);
        DaCronScheduleRepository cronRepo = mock(DaCronScheduleRepository.class);
        CityFleetConfigRepository fleetRepo = mock(CityFleetConfigRepository.class);
        VanManifestRepository manifestRepo = mock(VanManifestRepository.class);
        VanManifestItemRepository itemRepo = mock(VanManifestItemRepository.class);
        GridDataAdapter grid = mock(GridDataAdapter.class);
        CronEventProducer producer = mock(CronEventProducer.class);

        when(planRepo.findByCityIdAndValidForDateAndStatus(CITY, DATE, RoutePlanStatus.APPROVED))
                .thenReturn(List.of(RoutePlan.builder().id(PLAN).cityId(CITY).validForDate(DATE)
                        .status(RoutePlanStatus.APPROVED).revision(1).build()));
        when(fleetRepo.findByCityId(CITY))
                .thenReturn(Optional.of(CityFleetConfig.builder().cityId(CITY).capacityPackets(10).build()));
        when(grid.hexToDa(CITY, DATE)).thenReturn(Map.of(HEX, DA));
        when(cronRepo.findByRoutePlanId(PLAN)).thenReturn(List.of(
                DaCronSchedule.builder().routePlanId(PLAN).daId(DA).hexVertexId(VERTEX).vanId(VAN)
                        .meetingTimes("[]").cityId(CITY).validDate(DATE).build()));
        when(stopRepo.findByRoutePlanId(PLAN)).thenReturn(List.of(
                RoutePlanStop.builder().routePlanId(PLAN).vanId(VAN).loopIndex(0).stopSeq(1)
                        .nodeKind(StopNodeKind.MEETING_VERTEX).hexVertexId(VERTEX)
                        .plannedArrival(LocalTime.of(7, 30)).plannedDeparture(LocalTime.of(7, 35)).build()));
        when(manifestRepo.lockByVanLoopDate(any(), anyInt(), eq(DATE)))
                .thenAnswer(inv -> Optional.ofNullable(manifestStore.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(manifestRepo.saveAndFlush(any(VanManifest.class))).thenAnswer(inv -> {
            VanManifest m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            manifestStore.put(m.getVanId() + "|" + m.getLoopIndex(), m);
            return m;
        });
        when(itemRepo.countByManifestIdAndDirection(any(), any())).thenAnswer(inv -> (int) itemStore.stream()
                .filter(i -> i.getManifestId().equals(inv.getArgument(0)) && i.getDirection() == inv.getArgument(1)).count());
        when(itemRepo.findByParcelId(any())).thenAnswer(inv -> itemStore.stream()
                .filter(i -> i.getParcelId().equals(inv.getArgument(0))).toList());
        when(itemRepo.save(any(VanManifestItem.class))).thenAnswer(inv -> {
            VanManifestItem i = inv.getArgument(0);
            i.setId(UUID.randomUUID());
            itemStore.add(i);
            return i;
        });

        VanManifestServiceImpl service = new VanManifestServiceImpl(mock(com.oneday.routing.service.port.HubSortPort.class),
                mock(DaAccumulationPort.class), mock(FlightCutoffPort.class), planRepo, stopRepo, cronRepo, fleetRepo,
                manifestRepo, itemRepo, grid, producer, new RoutingProperties());
        HubFeedConsumer consumer = new HubFeedConsumer(inboundRepo, service);

        // The broker would hand this to the @RabbitListener; invoke it directly.
        UUID parcelId = UUID.randomUUID();
        consumer.onSortedForDelivery(new ParcelSortedForDeliveryEvent(
                parcelId, CITY, HEX, DATE, instant(LocalTime.of(7, 0)), instant(LocalTime.of(11, 0))));

        assertThat(buffer).hasSize(1); // recorded
        assertThat(itemStore).hasSize(1); // and bound in the same hop
        assertThat(itemStore.get(0).getParcelId()).isEqualTo(parcelId);
        assertThat(itemStore.get(0).getMeetingVertexId()).isEqualTo(VERTEX);
        assertThat(buffer.get(0).getKind()).isEqualTo(InboundKind.DELIVER);
    }

    private static Instant instant(LocalTime time) {
        return LocalDateTime.of(DATE, time).atZone(ClockConfig.IST).toInstant();
    }
}
