package com.oneday.routing.service.impl;

import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.VanLiveStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.dto.TelemetryAck;
import com.oneday.routing.dto.TelemetryType;
import com.oneday.routing.dto.VanTelemetryRequest;
import com.oneday.routing.events.RouteDeviationProducer;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.repository.VanLiveStatusRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.CustodyService;
import com.oneday.routing.service.model.CustodyResult;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.common.port.ScanLedgerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VanTrackingServiceImplTest {

    @Mock VanLiveStatusRepository liveRepository;
    @Mock VanManifestRepository manifestRepository;
    @Mock RoutePlanRepository planRepository;
    @Mock RoutePlanStopRepository stopRepository;
    @Mock CustodyService custodyService;
    @Mock RouteDeviationProducer deviationProducer;

    private static final UUID VAN = UUID.randomUUID();
    private static final UUID CITY = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID DA = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 6, 20);
    private static final LocalTime PLANNED = LocalTime.of(7, 50);

    private final Map<UUID, VanLiveStatus> liveStore = new HashMap<>();
    private final RoutingProperties properties = new RoutingProperties();

    private VanTrackingServiceImpl service;

    private void buildAt(LocalTime arrivalWallClock) {
        Instant now = ZonedDateTime.of(DAY, arrivalWallClock, ClockConfig.IST).toInstant();
        Clock clock = Clock.fixed(now, ClockConfig.IST);
        service = new VanTrackingServiceImpl(liveRepository, manifestRepository, planRepository, stopRepository,
                custodyService, deviationProducer, properties, clock);
    }

    @BeforeEach
    void setUp() {
        liveStore.clear();
        when(liveRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(liveStore.get(inv.getArgument(0))));
        when(liveRepository.save(any(VanLiveStatus.class))).thenAnswer(inv -> {
            VanLiveStatus s = inv.getArgument(0);
            liveStore.put(s.getVanId(), s);
            return s;
        });
        when(custodyService.record(any())).thenAnswer(inv -> {
            VanCustodyCommand c = inv.getArgument(0);
            return CustodyResult.recorded(c.parcelId(), c.type(), null);
        });
        VanManifest manifest = VanManifest.builder().id(UUID.randomUUID()).vanId(VAN).loopIndex(0)
                .validDate(DAY).routePlanId(PLAN).status(ManifestStatus.LOADED).build();
        when(manifestRepository.findByVanIdAndLoopIndexAndValidDate(VAN, 0, DAY)).thenReturn(Optional.of(manifest));
        when(manifestRepository.save(any(VanManifest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRepository.findById(PLAN)).thenReturn(Optional.of(
                RoutePlan.builder().id(PLAN).cityId(CITY).build()));
        when(stopRepository.findByRoutePlanIdAndVanIdAndLoopIndexOrderByStopSeq(PLAN, VAN, 0)).thenReturn(List.of(
                RoutePlanStop.builder().vanId(VAN).loopIndex(0).stopSeq(1).plannedArrival(PLANNED).build()));
    }

    @Test
    void gpsPing_overwritesLiveStatus_emitsNothing() {
        buildAt(LocalTime.of(7, 30));
        VanTelemetryRequest ping = new VanTelemetryRequest(TelemetryType.GPS, 12.97, 77.61, null, CITY,
                null, null, null, null, null, DRIVER);

        TelemetryAck ack = service.handle(VAN, ping);

        assertThat(ack.status()).isEqualTo("RECORDED");
        assertThat(liveStore.get(VAN).getLastLat()).isEqualTo(12.97);
        verify(deviationProducer, never()).emitVanArrived(any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(deviationProducer, never()).emitVanRunningLate(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void arrivedOnTime_emitsVanArrived_noLate_andStartsLoop() {
        buildAt(PLANNED); // exactly 07:50
        ArgumentCaptor<VanManifest> manifestCaptor = ArgumentCaptor.forClass(VanManifest.class);

        TelemetryAck ack = service.handle(VAN, arrived());

        assertThat(ack.minutesLate()).isZero();
        verify(deviationProducer).emitVanArrived(eq(VAN), eq(CITY), eq(PLAN), eq(0), eq(1), any(), any());
        verify(deviationProducer, never()).emitVanRunningLate(any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(manifestRepository).save(manifestCaptor.capture());
        assertThat(manifestCaptor.getValue().getStatus()).isEqualTo(ManifestStatus.IN_PROGRESS);
    }

    @Test
    void arrivedPastThreshold_emitsVanRunningLate() {
        properties.setLateThresholdMinutes(10);
        buildAt(LocalTime.of(8, 5)); // 15 min after planned 07:50

        TelemetryAck ack = service.handle(VAN, arrived());

        assertThat(ack.minutesLate()).isEqualTo(15);
        verify(deviationProducer).emitVanArrived(eq(VAN), eq(CITY), eq(PLAN), eq(0), eq(1), any(), any());
        verify(deviationProducer).emitVanRunningLate(VAN, CITY, PLAN, 0, 1, 15);
    }

    @Test
    void deliverScan_routesToCustodyAsVanToDa() {
        buildAt(LocalTime.of(8, 0));
        UUID parcel = UUID.randomUUID();
        VanTelemetryRequest scan = new VanTelemetryRequest(TelemetryType.DELIVER, null, null, null, CITY,
                0, 1, null, parcel, DA, DRIVER);

        TelemetryAck ack = service.handle(VAN, scan);

        assertThat(ack.status()).isEqualTo("RECORDED");
        ArgumentCaptor<VanCustodyCommand> cmd = ArgumentCaptor.forClass(VanCustodyCommand.class);
        verify(custodyService).record(cmd.capture());
        assertThat(cmd.getValue().type()).isEqualTo(ScanLedgerPort.VanScanType.VAN_TO_DA);
        assertThat(cmd.getValue().counterpartyDaId()).isEqualTo(DA);
    }

    @Test
    void collectScan_routesToCustodyAsDaToVan() {
        buildAt(LocalTime.of(8, 0));
        VanTelemetryRequest scan = new VanTelemetryRequest(TelemetryType.COLLECT, null, null, null, CITY,
                0, 1, null, UUID.randomUUID(), DA, DRIVER);

        service.handle(VAN, scan);

        ArgumentCaptor<VanCustodyCommand> cmd = ArgumentCaptor.forClass(VanCustodyCommand.class);
        verify(custodyService).record(cmd.capture());
        assertThat(cmd.getValue().type()).isEqualTo(ScanLedgerPort.VanScanType.DA_TO_VAN);
    }

    private VanTelemetryRequest arrived() {
        return new VanTelemetryRequest(TelemetryType.ARRIVED_AT_STOP, 12.97, 77.61, null, CITY,
                0, 1, UUID.randomUUID(), null, null, DRIVER);
    }
}
