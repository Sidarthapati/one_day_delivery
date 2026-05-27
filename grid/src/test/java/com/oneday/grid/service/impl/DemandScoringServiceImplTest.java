package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.repository.TileDemandSnapshotRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.GridService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemandScoringServiceImplTest {

    @Mock GridService gridService;
    @Mock TileRepository tileRepository;
    @Mock TileDemandSnapshotRepository snapshotRepository;
    @Mock M4DataLoader m4;
    // Grid extends BaseEntity (id is read-only), so we mock it as a @Mock field
    @Mock Grid grid;

    GridProperties properties = new GridProperties();
    DemandScoringServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final UUID gridId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 18);

    @BeforeEach
    void setUp() {
        lenient().when(grid.getId()).thenReturn(gridId);
        lenient().when(gridService.getGrid(cityId)).thenReturn(grid);
        lenient().when(snapshotRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new DemandScoringServiceImpl(gridService, tileRepository,
                snapshotRepository, properties, m4);
    }

    // ---- helpers ----------------------------------------------------------

    private Tile tile(UUID id) {
        Tile t = Tile.builder()
                .gridId(gridId).rowIdx(0).colIdx(0).active(true).build();
        t.setId(id);
        return t;
    }

    /** Stubs the four M4DataLoader methods with the given per-tile data maps. */
    private void stubM4(Map<UUID, Double> svcData, Map<UUID, Double> interData,
                        Map<UUID, Integer> currentData, Map<UUID, Double> histData) {
        lenient().when(m4.loadServiceTimeMins(anyInt())).thenReturn(svcData);
        lenient().when(m4.loadInterStopTravelMins(anyInt())).thenReturn(interData);
        lenient().when(m4.loadCurrentOrders(date)).thenReturn(currentData);
        lenient().when(m4.loadHistAvgOrders(date)).thenReturn(histData);
    }

    // ---- bootstrap mode ---------------------------------------------------

    @Test
    void computeAndPersistDemand_allM4DataMissing_runsInFullBootstrapMode() {
        UUID tileId = UUID.randomUUID();
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile(tileId)));
        stubM4(Map.of(), Map.of(), Map.of(), Map.of());

        List<TileDemandSnapshot> snapshots = service.computeAndPersistDemand(cityId, date);

        assertThat(snapshots).hasSize(1);
        TileDemandSnapshot snap = snapshots.get(0);
        assertThat(snap.getTileId()).isEqualTo(tileId);
        assertThat(snap.getSnapshotDate()).isEqualTo(date);
        assertThat(snap.getServiceTimeMin()).isEqualTo(12.0);    // bootstrap default
        assertThat(snap.getInterStopTravelMin()).isEqualTo(5.0); // bootstrap default
        assertThat(snap.getOrderEngagedMin()).isEqualTo(17.0);
        assertThat(snap.getHistAvgOrders()).isEqualTo(0.0);
        assertThat(snap.getCurrentOrders()).isEqualTo(0);
        assertThat(snap.getDemandScoreOrders()).isEqualTo(0.0);
        assertThat(snap.getDemandScoreMinutes()).isEqualTo(0.0);
        assertThat(snap.isBootstrapped()).isTrue();
    }

    @Test
    void computeAndPersistDemand_queryThrows_fallsBackToBootstrap() {
        UUID tileId = UUID.randomUUID();
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile(tileId)));
        // Simulate every M4DataLoader method blowing up (e.g. table doesn't exist pre-M4).
        when(m4.loadServiceTimeMins(anyInt())).thenThrow(new RuntimeException("relation does not exist"));
        when(m4.loadInterStopTravelMins(anyInt())).thenThrow(new RuntimeException("relation does not exist"));
        when(m4.loadCurrentOrders(any())).thenThrow(new RuntimeException("relation does not exist"));
        when(m4.loadHistAvgOrders(any())).thenThrow(new RuntimeException("relation does not exist"));

        List<TileDemandSnapshot> snapshots = service.computeAndPersistDemand(cityId, date);

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).isBootstrapped()).isTrue();
        assertThat(snapshots.get(0).getServiceTimeMin()).isEqualTo(12.0);
        assertThat(snapshots.get(0).getInterStopTravelMin()).isEqualTo(5.0);
    }

    // ---- real data --------------------------------------------------------

    @Test
    void computeAndPersistDemand_realData_appliesDemandFormulas() {
        UUID tileId = UUID.randomUUID();
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile(tileId)));
        stubM4(
                Map.of(tileId, 15.0),   // svc time = 15 min
                Map.of(tileId, 8.0),    // inter-stop = 8 min
                Map.of(tileId, 20),     // current orders = 20
                Map.of(tileId, 10.0)    // hist avg = 10
        );

        List<TileDemandSnapshot> snapshots = service.computeAndPersistDemand(cityId, date);

        assertThat(snapshots).hasSize(1);
        TileDemandSnapshot snap = snapshots.get(0);
        assertThat(snap.getServiceTimeMin()).isEqualTo(15.0);
        assertThat(snap.getInterStopTravelMin()).isEqualTo(8.0);
        assertThat(snap.getOrderEngagedMin()).isEqualTo(23.0);
        assertThat(snap.getHistAvgOrders()).isEqualTo(10.0);
        assertThat(snap.getCurrentOrders()).isEqualTo(20);
        // demandOrders = 0.70 * 10 + 0.30 * 20 = 13.0
        assertThat(snap.getDemandScoreOrders()).isEqualTo(13.0);
        // demandMinutes = 13 * 23 = 299
        assertThat(snap.getDemandScoreMinutes()).isEqualTo(299.0);
        assertThat(snap.isBootstrapped()).isFalse();
    }

    @Test
    void computeAndPersistDemand_mixedTiles_perTileBootstrapFlag() {
        // tileA has real svc + inter data; tileB has none → uses city-wide avg
        UUID tileA = UUID.randomUUID();
        UUID tileB = UUID.randomUUID();
        when(tileRepository.findByGridIdAndActiveTrue(gridId))
                .thenReturn(List.of(tile(tileA), tile(tileB)));
        stubM4(
                Map.of(tileA, 20.0),
                Map.of(tileA, 6.0),
                Map.of(),                       // no current orders
                Map.of(tileA, 5.0)              // only tileA has hist
        );

        List<TileDemandSnapshot> snapshots = service.computeAndPersistDemand(cityId, date);

        assertThat(snapshots).hasSize(2);
        TileDemandSnapshot snapA = snapshots.stream()
                .filter(s -> s.getTileId().equals(tileA)).findFirst().orElseThrow();
        TileDemandSnapshot snapB = snapshots.stream()
                .filter(s -> s.getTileId().equals(tileB)).findFirst().orElseThrow();

        assertThat(snapA.isBootstrapped()).isFalse();
        assertThat(snapA.getServiceTimeMin()).isEqualTo(20.0);
        assertThat(snapA.getInterStopTravelMin()).isEqualTo(6.0);
        assertThat(snapA.getHistAvgOrders()).isEqualTo(5.0);

        // tileB falls back to city-wide avg (which is just tileA's values since it's the only sample)
        assertThat(snapB.isBootstrapped()).isTrue();
        assertThat(snapB.getServiceTimeMin()).isEqualTo(20.0);
        assertThat(snapB.getInterStopTravelMin()).isEqualTo(6.0);
        assertThat(snapB.getHistAvgOrders()).isEqualTo(0.0);
        assertThat(snapB.getCurrentOrders()).isEqualTo(0);
    }

    @Test
    void computeAndPersistDemand_noActiveTiles_returnsEmptyAndSavesEmpty() {
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of());

        List<TileDemandSnapshot> snapshots = service.computeAndPersistDemand(cityId, date);

        assertThat(snapshots).isEmpty();
    }

    @Test
    void computeAndPersistDemand_passesSavedSnapshotsToRepository() {
        UUID tileId = UUID.randomUUID();
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile(tileId)));
        stubM4(
                Map.of(tileId, 10.0),
                Map.of(tileId, 4.0),
                Map.of(tileId, 5),
                Map.of(tileId, 8.0)
        );

        service.computeAndPersistDemand(cityId, date);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TileDemandSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(snapshotRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getTileId()).isEqualTo(tileId);
    }

    // ---- bootstrap-mode detection (log path) ------------------------------

    @Test
    void computeAndPersistDemand_onlyHistAndCurrentMissing_notFullBootstrap() {
        // svc + inter present, but no current/hist orders → snapshot uses real ops data
        // and reports bootstrapped=false (operational data drives the bootstrap flag).
        UUID tileId = UUID.randomUUID();
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(tile(tileId)));
        stubM4(
                Map.of(tileId, 12.0),
                Map.of(tileId, 5.0),
                Map.of(),
                Map.of()
        );

        List<TileDemandSnapshot> snapshots = service.computeAndPersistDemand(cityId, date);

        TileDemandSnapshot snap = snapshots.get(0);
        assertThat(snap.isBootstrapped()).isFalse();
        assertThat(snap.getHistAvgOrders()).isEqualTo(0.0);
        assertThat(snap.getCurrentOrders()).isEqualTo(0);
        assertThat(snap.getDemandScoreOrders()).isEqualTo(0.0);
        assertThat(snap.getDemandScoreMinutes()).isEqualTo(0.0);
    }
}
