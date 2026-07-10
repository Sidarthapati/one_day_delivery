package com.oneday.routing.service.impl;

import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.CityFleetConfig;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.ManifestStatus;
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
import com.oneday.routing.service.model.BindOutcome;
import com.oneday.routing.service.model.ReconcileSummary;
import com.oneday.routing.service.port.DaAccumulationPort;
import com.oneday.routing.service.port.FlightCutoffPort;
import com.oneday.routing.service.port.HubSortPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // stateful fakes below; not every stub fires per test
class VanManifestServiceImplTest {

    @Mock HubSortPort hubSortPort;
    @Mock DaAccumulationPort daAccumulationPort;
    @Mock FlightCutoffPort flightCutoffPort;
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

    private final Map<String, VanManifest> manifestStore = new HashMap<>(); // van|loop → manifest
    private final List<VanManifestItem> itemStore = new ArrayList<>();

    private VanManifestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VanManifestServiceImpl(hubSortPort, daAccumulationPort, flightCutoffPort,
                planRepository, stopRepository, cronRepository, fleetConfigRepository,
                manifestRepository, itemRepository, gridDataAdapter, cronEventProducer, properties);

        when(planRepository.findByCityIdAndValidForDateAndStatus(CITY, DATE, RoutePlanStatus.APPROVED))
                .thenReturn(List.of(RoutePlan.builder().id(PLAN).cityId(CITY).validForDate(DATE)
                        .status(RoutePlanStatus.APPROVED).revision(1).build()));
        when(fleetConfigRepository.findByCityId(CITY))
                .thenReturn(Optional.of(CityFleetConfig.builder().cityId(CITY).capacityPackets(1).build()));
        when(gridDataAdapter.hexToDa(CITY, DATE)).thenReturn(Map.of(HEX, DA));
        when(cronRepository.findByRoutePlanId(PLAN)).thenReturn(List.of(
                DaCronSchedule.builder().routePlanId(PLAN).daId(DA).hexVertexId(VERTEX).vanId(VAN)
                        .meetingTimes("[]").cityId(CITY).validDate(DATE).build()));
        when(stopRepository.findByRoutePlanId(PLAN)).thenReturn(List.of(
                stop(0, 1, LocalTime.of(7, 30)),
                stop(1, 1, LocalTime.of(8, 30))));

        // Stateful fakes so capacity counting + the bump (moving an item between manifests) behave for real.
        when(manifestRepository.lockByVanLoopDate(any(), anyInt(), eq(DATE)))
                .thenAnswer(inv -> Optional.ofNullable(manifestStore.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(manifestRepository.saveAndFlush(any(VanManifest.class))).thenAnswer(inv -> {
            VanManifest m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            manifestStore.put(m.getVanId() + "|" + m.getLoopIndex(), m);
            return m;
        });
        when(itemRepository.countByManifestIdAndDirection(any(), any())).thenAnswer(inv -> (int) itemStore.stream()
                .filter(i -> i.getManifestId().equals(inv.getArgument(0)) && i.getDirection() == inv.getArgument(1)).count());
        when(itemRepository.findByManifestIdAndDirection(any(), any())).thenAnswer(inv -> itemStore.stream()
                .filter(i -> i.getManifestId().equals(inv.getArgument(0)) && i.getDirection() == inv.getArgument(1)).toList());
        when(itemRepository.findByParcelId(any())).thenAnswer(inv -> itemStore.stream()
                .filter(i -> i.getParcelId().equals(inv.getArgument(0))).toList());
        when(itemRepository.save(any(VanManifestItem.class))).thenAnswer(inv -> {
            VanManifestItem i = inv.getArgument(0);
            if (i.getId() == null) i.setId(UUID.randomUUID());
            if (!itemStore.contains(i)) itemStore.add(i);
            return i;
        });
        when(itemRepository.findByManifestId(any())).thenAnswer(inv -> itemStore.stream()
                .filter(i -> i.getManifestId().equals(inv.getArgument(0))).toList());
        when(manifestRepository.save(any(VanManifest.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bindsEarliestLoopWithRoom_overflowsOnlyWhenAllLoopsFull() {
        // capacity 1/loop, 2 loops. p1 fills loop 0, p2 rides the next loop out (1), p3 overflows.
        UUID p1 = bind(LocalTime.of(8, 15)).parcelId();
        UUID p2 = bind(LocalTime.of(8, 15)).parcelId();
        UUID p3 = UUID.randomUUID();
        BindOutcome out3 = service.bindDelivery(CITY, DATE, p3, HEX, instant(LocalTime.of(8, 15)));

        assertThat(loopOfParcel(p1)).isEqualTo(0);
        assertThat(loopOfParcel(p2)).isEqualTo(1); // earliest loop WITH ROOM — deadline no longer blocks loop 1
        assertThat(out3.outcome()).isEqualTo(BindOutcome.Outcome.OVERFLOW);
        verify(cronEventProducer, times(1)).emitLoopOverflow(eq(CITY), eq(VAN), eq(p3), eq(-1), any());
    }

    @Test
    void pastDeadline_stillBindsEarliest_noOverflow() {
        // Deadline already in the past (06:00). Old behaviour overflowed; now it rides the next loop out.
        BindOutcome out = service.bindDelivery(CITY, DATE, UUID.randomUUID(), HEX, instant(LocalTime.of(6, 0)));

        assertThat(out.outcome()).isEqualTo(BindOutcome.Outcome.BOUND);
        assertThat(out.loopIndex()).isZero();
        verify(cronEventProducer, never()).emitLoopOverflow(any(), any(), any(), anyInt(), any());
    }

    @Test
    void nullDeadline_bindsEarliest_noNpe() {
        BindOutcome out = service.bindDelivery(CITY, DATE, UUID.randomUUID(), HEX, null);

        assertThat(out.outcome()).isEqualTo(BindOutcome.Outcome.BOUND);
        assertThat(out.loopIndex()).isZero();
    }

    @Test
    void collect_bindsEarliestLoop_evenWithNoFlightCutoff() {
        when(flightCutoffPort.outboundFlightCutoff(CITY, DATE)).thenReturn(Optional.empty());

        BindOutcome out = service.bindCollect(CITY, DATE, UUID.randomUUID(), DA);

        assertThat(out.outcome()).isEqualTo(BindOutcome.Outcome.BOUND);
        assertThat(out.loopIndex()).isZero(); // earliest loop back, not the latest
    }

    @Test
    void unresolvedHex_isReportedNotBoundNotOverflowed() {
        BindOutcome out = service.bindDelivery(CITY, DATE, UUID.randomUUID(), UUID.randomUUID(), instant(LocalTime.of(9, 0)));

        assertThat(out.outcome()).isEqualTo(BindOutcome.Outcome.UNRESOLVED);
        verify(cronEventProducer, never()).emitLoopOverflow(any(), any(), any(), anyInt(), any());
        assertThat(itemStore).isEmpty();
    }

    @Test
    void replayedParcel_isIdempotent() {
        UUID parcel = UUID.randomUUID();
        service.bindDelivery(CITY, DATE, parcel, HEX, instant(LocalTime.of(9, 0)));
        BindOutcome second = service.bindDelivery(CITY, DATE, parcel, HEX, instant(LocalTime.of(9, 0)));

        assertThat(second.outcome()).isEqualTo(BindOutcome.Outcome.BOUND);
        assertThat(itemStore).hasSize(1); // not double-bound
    }

    @Test
    void reconcileToLivePlan_repointsManifests_reroutesPlanned_keepsOrEscalatesLoaded() {
        UUID oldPlan = UUID.randomUUID();
        UUID strandedVertex = UUID.randomUUID(); // a vertex the live plan no longer visits
        // High capacity so the re-routed PLANNED item rebinds rather than overflowing.
        when(fleetConfigRepository.findByCityId(CITY))
                .thenReturn(Optional.of(CityFleetConfig.builder().cityId(CITY).capacityPackets(100).build()));
        // Two plans for the day: the live one (PLAN, from setUp) and a superseded one.
        when(planRepository.findByCityIdAndValidForDate(CITY, DATE)).thenReturn(List.of(
                RoutePlan.builder().id(PLAN).cityId(CITY).validForDate(DATE).status(RoutePlanStatus.APPROVED).revision(2).build(),
                RoutePlan.builder().id(oldPlan).cityId(CITY).validForDate(DATE).status(RoutePlanStatus.SUPERSEDED).revision(1).build()));

        // The superseded plan's two manifests on VAN (loops 0 and 1, both surviving in the live plan).
        VanManifest o0 = manifest(oldPlan, 0);
        VanManifest o1 = manifest(oldPlan, 1);
        manifestStore.put(VAN + "|0", o0);
        manifestStore.put(VAN + "|1", o1);
        when(manifestRepository.findByRoutePlanId(oldPlan)).thenReturn(List.of(o0, o1));

        UUID pPlanned = UUID.randomUUID(), pKept = UUID.randomUUID(), pStranded = UUID.randomUUID();
        itemStore.add(item(o0.getId(), pPlanned, ManifestItemStatus.PLANNED, VERTEX, 1));        // not loaded → reroute
        itemStore.add(item(o1.getId(), pKept, ManifestItemStatus.LOADED, VERTEX, 1));            // loaded, stop survives → keep
        itemStore.add(item(o1.getId(), pStranded, ManifestItemStatus.LOADED, strandedVertex, 2)); // loaded, stop gone → escalate

        ReconcileSummary summary = service.reconcileToLivePlan(CITY, DATE);

        assertThat(o0.getRoutePlanId()).isEqualTo(PLAN); // both manifests repointed to the live plan
        assertThat(o1.getRoutePlanId()).isEqualTo(PLAN);
        assertThat(summary.repointedManifests()).isEqualTo(2);
        assertThat(summary.reboundPlanned()).isEqualTo(1);
        assertThat(summary.keptInCustody()).isEqualTo(1);
        assertThat(summary.escalated()).isEqualTo(1);

        // the PLANNED item is detached (EXCEPTION) and a fresh PLANNED item re-bound for the same parcel
        List<VanManifestItem> forPlanned = itemStore.stream().filter(i -> i.getParcelId().equals(pPlanned)).toList();
        assertThat(forPlanned).hasSize(2);
        assertThat(forPlanned).anyMatch(i -> i.getStatus() == ManifestItemStatus.EXCEPTION);
        assertThat(forPlanned).anyMatch(i -> i.getStatus() == ManifestItemStatus.PLANNED);

        // loaded items are never moved; the surviving one is untouched, the stranded one escalated.
        assertThat(itemStore.stream().filter(i -> i.getParcelId().equals(pKept)).findFirst().orElseThrow().getStatus())
                .isEqualTo(ManifestItemStatus.LOADED);
        verify(cronEventProducer).emitLoopOverflow(eq(CITY), eq(VAN), eq(pStranded), eq(1), any());
    }

    private VanManifest manifest(UUID planId, int loop) {
        return VanManifest.builder().id(UUID.randomUUID()).routePlanId(planId).vanId(VAN).loopIndex(loop)
                .validDate(DATE).status(ManifestStatus.LOADED).build();
    }

    private VanManifestItem item(UUID manifestId, UUID parcelId, ManifestItemStatus status, UUID vertex, int seq) {
        return VanManifestItem.builder().id(UUID.randomUUID()).manifestId(manifestId).parcelId(parcelId)
                .direction(HandoffDirection.DELIVER).stopSeq(seq).meetingVertexId(vertex).counterpartyDaId(DA)
                .status(status).build();
    }

    private BindOutcome bind(LocalTime deadline) {
        return service.bindDelivery(CITY, DATE, UUID.randomUUID(), HEX, instant(deadline));
    }

    private int loopOfParcel(UUID parcelId) {
        VanManifestItem item = itemStore.stream().filter(i -> i.getParcelId().equals(parcelId)).findFirst().orElseThrow();
        return manifestStore.values().stream().filter(m -> m.getId().equals(item.getManifestId()))
                .findFirst().orElseThrow().getLoopIndex();
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
