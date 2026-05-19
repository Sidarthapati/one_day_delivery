package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.osrm.OsrmClient;
import com.oneday.grid.service.osrm.TileEdge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OsrmMatrixServiceImplTest {

    @Mock GridService gridService;
    @Mock TileRepository tileRepository;
    @Mock GridProperties properties;
    @Mock OsrmClient osrmClient;
    @Mock Grid grid;

    OsrmMatrixServiceImpl service;

    final UUID cityId = UUID.randomUUID();
    final UUID gridId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        GridProperties.Osrm osrm = new GridProperties.Osrm(); // baseUrl="http://localhost:5000", threshold=600
        when(properties.getOsrm()).thenReturn(osrm);
        lenient().when(grid.getId()).thenReturn(gridId);
        lenient().when(grid.getOriginLat()).thenReturn(12.0);
        lenient().when(grid.getOriginLon()).thenReturn(77.0);
        lenient().when(grid.getTileDeltaLat()).thenReturn(0.018);
        lenient().when(grid.getTileDeltaLon()).thenReturn(0.020);
        when(gridService.getGrid(cityId)).thenReturn(grid);

        service = new OsrmMatrixServiceImpl(gridService, tileRepository, properties);
        ReflectionTestUtils.setField(service, "osrmClient", osrmClient);
    }

    private Tile tile(int row, int col) {
        Tile t = Tile.builder().gridId(gridId).rowIdx(row).colIdx(col).active(true).build();
        t.setId(UUID.randomUUID());
        return t;
    }

    // ---- A2 tests ----------------------------------------------------------

    @Test
    void buildMatrix_2tiles_callsOsrmWithTwoCoordinates() {
        List<Tile> tiles = List.of(tile(0, 0), tile(0, 1));
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(tiles);
        when(osrmClient.getTable(anyList())).thenReturn(new double[][]{{0, 300}, {300, 0}});

        service.computeAdjacencyMatrix(cityId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(osrmClient).getTable(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void buildMatrix_emptyTileList_returnsEmptyMapWithoutCallingOsrm() {
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of());

        Map<UUID, List<TileEdge>> result = service.computeAdjacencyMatrix(cityId);

        assertThat(result).isEmpty();
        verify(osrmClient, never()).getTable(any());
    }

    @Test
    void buildMatrix_osrmReturnsSymmetricMatrix_createsAllDirectedEdges() {
        Tile t0 = tile(0, 0), t1 = tile(0, 1), t2 = tile(1, 0);
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(t0, t1, t2));

        double[][] durations = {
                {0, 300, 300},
                {300, 0, 300},
                {300, 300, 0}
        };
        when(osrmClient.getTable(anyList())).thenReturn(durations);

        Map<UUID, List<TileEdge>> result = service.computeAdjacencyMatrix(cityId);

        assertThat(result).hasSize(3);
        long totalEdges = result.values().stream().mapToLong(List::size).sum();
        assertThat(totalEdges).isEqualTo(6);
        result.values().forEach(edges -> {
            assertThat(edges).hasSize(2);
            edges.forEach(e -> assertThat(e.travelTimeSec()).isEqualTo(300));
        });
    }

    @Test
    void buildMatrix_zeroDurationCell_edgeSkipped() {
        Tile t0 = tile(0, 0), t1 = tile(0, 1);
        when(tileRepository.findByGridIdAndActiveTrue(gridId)).thenReturn(List.of(t0, t1));

        // durations[0][1] = 0 simulates an unreachable pair → should be skipped
        double[][] durations = {{0, 0}, {300, 0}};
        when(osrmClient.getTable(anyList())).thenReturn(durations);

        Map<UUID, List<TileEdge>> result = service.computeAdjacencyMatrix(cityId);

        // t0 has no reachable neighbor (d=0 skipped); t1 reaches t0 (d=300)
        assertThat(result.get(t0.getId())).isEmpty();
        assertThat(result.get(t1.getId())).hasSize(1)
                .extracting(TileEdge::toTileId).containsExactly(t0.getId());
    }
}
