package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.PincodeMapping;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.GridVertexRepository;
import com.oneday.grid.repository.PincodeMappingRepository;
import com.oneday.grid.repository.TileDemandSnapshotRepository;
import com.oneday.grid.repository.TileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GridServiceImplTest {

    @Mock GridRepository gridRepository;
    @Mock TileRepository tileRepository;
    @Mock PincodeMappingRepository pincodeMappingRepository;
    @Mock GridVertexRepository gridVertexRepository;
    @Mock TileDemandSnapshotRepository demandSnapshotRepository;
    @Mock DaTileAssignmentRepository assignmentRepository;
    @Mock ResourceLoader resourceLoader;
    @Mock GridProperties gridProperties;
    // Grid extends BaseEntity (id is read-only), so we mock it as a @Mock field
    @Mock Grid grid;

    GridServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final UUID gridId  = UUID.randomUUID();

    private static final double DELTA_LAT = 2.0 / 111.32;
    private static final double DELTA_LON = 2.0 / (111.32 * Math.cos(Math.toRadians(12.9)));

    @BeforeEach
    void setUp() {
        lenient().when(grid.getCityId()).thenReturn(cityId);
        lenient().when(grid.getId()).thenReturn(gridId);
        when(gridRepository.findAll()).thenReturn(List.of(grid));
        service = new GridServiceImpl(gridRepository, tileRepository,
                pincodeMappingRepository, gridVertexRepository,
                demandSnapshotRepository, assignmentRepository,
                resourceLoader, gridProperties);
        service.loadGridCache();
    }

    // ---- getGrid -----------------------------------------------------------

    @Test
    void getGrid_knownCityId_returnsGrid() {
        assertThat(service.getGrid(cityId)).isSameAs(grid);
    }

    @Test
    void getGrid_unknownCityId_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.getGrid(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No grid found");
    }

    // ---- checkServiceability -----------------------------------------------

    @Test
    void checkServiceability_knownServiceablePincode_returnsTrue() {
        UUID tileId = UUID.randomUUID();
        PincodeMapping pm = PincodeMapping.builder()
                .cityId(cityId).pincode("110001").tileId(tileId).serviceable(true).build();

        when(pincodeMappingRepository.findByCityIdAndPincode(cityId, "110001"))
                .thenReturn(Optional.of(pm));

        ServiceabilityResponse resp = service.checkServiceability(cityId, "110001");

        assertThat(resp.serviceable()).isTrue();
        assertThat(resp.tileId()).isEqualTo(tileId);
    }

    @Test
    void checkServiceability_unknownPincode_returnsFalse() {
        when(pincodeMappingRepository.findByCityIdAndPincode(cityId, "999999"))
                .thenReturn(Optional.empty());

        ServiceabilityResponse resp = service.checkServiceability(cityId, "999999");

        assertThat(resp.serviceable()).isFalse();
        assertThat(resp.tileId()).isNull();
    }

    @Test
    void checkServiceability_knownButNotServiceable_returnsFalse() {
        PincodeMapping pm = PincodeMapping.builder()
                .cityId(cityId).pincode("110002").tileId(null).serviceable(false).build();

        when(pincodeMappingRepository.findByCityIdAndPincode(cityId, "110002"))
                .thenReturn(Optional.of(pm));

        assertThat(service.checkServiceability(cityId, "110002").serviceable()).isFalse();
    }

    // ---- getTileAt ---------------------------------------------------------

    private void stubGridCoords() {
        when(grid.getOriginLat()).thenReturn(12.0);
        when(grid.getOriginLon()).thenReturn(77.0);
        when(grid.getTileDeltaLat()).thenReturn(DELTA_LAT);
        when(grid.getTileDeltaLon()).thenReturn(DELTA_LON);
    }

    @Test
    void getTileAt_coordsInFirstTile_returnsTileAt00() {
        stubGridCoords();
        // Point inside tile (row=0, col=0): origin + 0.5 * delta
        double lat = 12.0 + 0.5 * DELTA_LAT;
        double lon = 77.0 + 0.5 * DELTA_LON;

        UUID tileId = UUID.randomUUID();
        Tile tile = Tile.builder().gridId(gridId).rowIdx(0).colIdx(0).active(true).build();
        tile.setId(tileId);

        when(tileRepository.findByGridIdAndRowIdxAndColIdx(gridId, 0, 0))
                .thenReturn(Optional.of(tile));

        TileAtResponse resp = service.getTileAt(cityId, lat, lon);

        assertThat(resp.rowIdx()).isEqualTo(0);
        assertThat(resp.colIdx()).isEqualTo(0);
        assertThat(resp.active()).isTrue();
    }

    @Test
    void getTileAt_coordsInTile23_returnsTileAt23() {
        stubGridCoords();
        double lat = 12.0 + 2.7 * DELTA_LAT; // row = floor(2.7) = 2
        double lon = 77.0 + 3.4 * DELTA_LON; // col = floor(3.4) = 3

        Tile tile = Tile.builder().gridId(gridId).rowIdx(2).colIdx(3).active(true).build();
        tile.setId(UUID.randomUUID());

        when(tileRepository.findByGridIdAndRowIdxAndColIdx(gridId, 2, 3))
                .thenReturn(Optional.of(tile));

        TileAtResponse resp = service.getTileAt(cityId, lat, lon);

        assertThat(resp.rowIdx()).isEqualTo(2);
        assertThat(resp.colIdx()).isEqualTo(3);
    }

    @Test
    void getTileAt_noTileInRepository_throwsIllegalArgument() {
        stubGridCoords();
        double lat = 12.0 + 0.5 * DELTA_LAT;
        double lon = 77.0 + 0.5 * DELTA_LON;

        when(tileRepository.findByGridIdAndRowIdxAndColIdx(gridId, 0, 0))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTileAt(cityId, lat, lon))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No tile at row=0, col=0");
    }
}
