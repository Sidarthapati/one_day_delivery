package com.oneday.sla.service.impl;

import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.domain.enums.SlaState;
import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.common.port.StageContactPort;
import com.oneday.sla.domain.PriorityBand;
import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.dto.SlaClusterResponse;
import com.oneday.sla.repository.SlaActionRepository;
import com.oneday.sla.repository.SlaEscalationRepository;
import com.oneday.sla.repository.SlaLegRepository;
import com.oneday.sla.repository.SlaShipmentRepository;
import com.oneday.sla.service.WeatherService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Clustering invariants: shared cause groups together, worst band floats up, one desk per cluster. */
class SlaClusterTest {

    private final SlaShipmentRepository shipmentRepo = mock(SlaShipmentRepository.class);
    private final StageContactPort stageContactPort = mock(StageContactPort.class);
    private final SlaQueryServiceImpl svc = new SlaQueryServiceImpl(
            shipmentRepo, mock(SlaLegRepository.class), mock(SlaEscalationRepository.class),
            mock(SlaActionRepository.class), mock(CourierOnShipmentPort.class), stageContactPort,
            mock(ShipmentContactPort.class), mock(WeatherService.class));

    private SlaShipment ship(String ref, String origin, String dest, SlaLegType leg,
                             SlaState state, PriorityBand band, boolean breached, double score) {
        SlaShipment s = new SlaShipment();
        s.setShipmentRef(ref);
        s.setOriginCity(origin);
        s.setDestCity(dest);
        s.setCurrentLeg(leg);
        s.setOverallState(state);
        s.setBand(band);
        s.setBreached(breached);
        s.setPriorityScore(score);
        return s;
    }

    @Test
    void groupsByCauseRanksWorstBandFirstAndResolvesOneDesk() {
        when(stageContactPort.hubDesk(any())).thenReturn(
                Optional.of(new StageContactPort.Contact("BLR Hub Desk", "+91900000001", "HUB_OPERATOR")));
        when(stageContactPort.ghaDesk()).thenReturn(
                Optional.of(new StageContactPort.Contact("GHA Desk", "+91900000002", "AIRLINE_GHA")));

        when(shipmentRepo.findByClosedAtIsNull()).thenReturn(List.of(
                // Two air parcels on the same lane → one AIR/BLR→DEL cluster, one of them CRITICAL.
                ship("A1", "BLR", "DEL", SlaLegType.AIR, SlaState.BREACHED, PriorityBand.CRITICAL, true, 3_000_100),
                ship("A2", "BLR", "DEL", SlaLegType.DEST_AIRPORT, SlaState.RED, PriorityBand.HIGH, false, 2_000_050),
                // Three hub parcels in BLR, all HIGH → one bigger HIGH cluster.
                ship("H1", "BLR", "MAA", SlaLegType.ORIGIN_HUB, SlaState.RED, PriorityBand.HIGH, false, 2_000_020),
                ship("H2", "BLR", "HYD", SlaLegType.ORIGIN_HUB, SlaState.RED, PriorityBand.HIGH, false, 2_000_010),
                ship("H3", "BLR", "HYD", SlaLegType.ORIGIN_HUB, SlaState.GREEN, PriorityBand.HIGH, false, 2_000_005),
                // A WATCH-band parcel is no fire — even AMBER — and must never appear.
                ship("W1", "BLR", "DEL", SlaLegType.LAST_MILE, SlaState.AMBER, PriorityBand.WATCH, false, 1_000_000)));

        List<SlaClusterResponse.Cluster> clusters = svc.clusters(null).clusters();

        // Two clusters (AIR lane + BLR hub); the WATCH-band parcel is excluded. H3 is colour-GREEN but
        // HIGH band (racing a cutoff) and still clusters — the fire cut is the band, not the colour.
        assertThat(clusters).hasSize(2);

        // Worst band first: the AIR cluster carries a CRITICAL member, so it tops the all-HIGH hub cluster.
        SlaClusterResponse.Cluster air = clusters.get(0);
        assertThat(air.stage()).isEqualTo("AIR");
        assertThat(air.scope()).isEqualTo("BLR→DEL");
        assertThat(air.band()).isEqualTo(PriorityBand.CRITICAL);
        assertThat(air.size()).isEqualTo(2);
        assertThat(air.breachedCount()).isEqualTo(1);
        assertThat(air.contact().role()).isEqualTo("AIRLINE_GHA");
        assertThat(air.refs()).containsExactly("A1", "A2"); // worst score first

        SlaClusterResponse.Cluster hub = clusters.get(1);
        assertThat(hub.stage()).isEqualTo("HUB");
        assertThat(hub.band()).isEqualTo(PriorityBand.HIGH);
        assertThat(hub.size()).isEqualTo(3);
        assertThat(hub.contact().role()).isEqualTo("HUB_OPERATOR");
    }
}
