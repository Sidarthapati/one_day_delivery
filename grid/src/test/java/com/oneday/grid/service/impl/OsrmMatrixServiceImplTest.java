package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.osrm.OsrmClient;
import com.oneday.grid.service.osrm.TileEdge;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OsrmMatrixServiceImplTest {

    @Mock GridService gridService;
    @Mock HexRepository hexRepository;
    @Mock GridProperties properties;
    @Mock OsrmClient osrmClient;
    @Mock H3Core h3Core;
    @Mock Grid grid;

    OsrmMatrixServiceImpl service;

    final UUID cityId = UUID.randomUUID();
    final UUID gridId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        GridProperties.Osrm osrm = new GridProperties.Osrm(); // baseUrl="http://localhost:5000", threshold=600
        when(properties.getOsrm()).thenReturn(osrm);
        lenient().when(grid.getId()).thenReturn(gridId);
        lenient().when(h3Core.cellToLatLng(anyLong())).thenReturn(new LatLng(12.0, 77.0));
        when(gridService.getGrid(cityId)).thenReturn(grid);

        service = new OsrmMatrixServiceImpl(gridService, hexRepository, properties, h3Core);
        ReflectionTestUtils.setField(service, "osrmClient", osrmClient);
    }

    private Hex hex() {
        Hex h = Hex.builder().h3GridId(gridId).h3Index(0L).active(true).build();
        h.setId(UUID.randomUUID());
        return h;
    }

    // ---- A2 tests ----------------------------------------------------------

    @Test
    void buildMatrix_2tiles_callsOsrmWithTwoCoordinates() {
        List<Hex> hexes = List.of(hex(), hex());
        when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(hexes);
        when(osrmClient.getTable(anyList())).thenReturn(new double[][]{{0, 300}, {300, 0}});

        service.computeAdjacencyMatrix(cityId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(osrmClient).getTable(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void buildMatrix_emptyTileList_returnsEmptyMapWithoutCallingOsrm() {
        when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(List.of());

        Map<UUID, List<TileEdge>> result = service.computeAdjacencyMatrix(cityId);

        assertThat(result).isEmpty();
        verify(osrmClient, never()).getTable(any());
    }

    @Test
    void buildMatrix_osrmReturnsSymmetricMatrix_createsAllDirectedEdges() {
        Hex h0 = hex(), h1 = hex(), h2 = hex();
        when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(List.of(h0, h1, h2));

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
        Hex h0 = hex(), h1 = hex();
        when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(List.of(h0, h1));

        // durations[0][1] = 0 simulates an unreachable pair → should be skipped
        double[][] durations = {{0, 0}, {300, 0}};
        when(osrmClient.getTable(anyList())).thenReturn(durations);

        Map<UUID, List<TileEdge>> result = service.computeAdjacencyMatrix(cityId);

        // h0 has no reachable neighbor (d=0 skipped); h1 reaches h0 (d=300)
        assertThat(result.get(h0.getId())).isEmpty();
        assertThat(result.get(h1.getId())).hasSize(1)
                .extracting(TileEdge::toHexId).containsExactly(h0.getId());
    }
}
