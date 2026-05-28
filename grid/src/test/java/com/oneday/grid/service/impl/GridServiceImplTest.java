package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.PincodeMapping;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexDemandSnapshotRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.repository.HexVertexRepository;
import com.oneday.grid.repository.PincodeMappingRepository;
import com.uber.h3core.H3Core;
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
    @Mock HexRepository hexRepository;
    @Mock PincodeMappingRepository pincodeMappingRepository;
    @Mock HexVertexRepository hexVertexRepository;
    @Mock HexDemandSnapshotRepository demandSnapshotRepository;
    @Mock DaHexAssignmentRepository assignmentRepository;
    @Mock ResourceLoader resourceLoader;
    @Mock GridProperties gridProperties;
    @Mock H3Core h3Core;
    @Mock Grid grid;

    GridServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final UUID gridId  = UUID.randomUUID();

    // Pre-computed H3 index constant — avoids real H3Core calls in unit tests
    private static final long TEST_HEX_INDEX = 0x872be10caffffe0L;

    @BeforeEach
    void setUp() {
        lenient().when(grid.getCityId()).thenReturn(cityId);
        lenient().when(grid.getId()).thenReturn(gridId);
        lenient().when(grid.getH3Resolution()).thenReturn(7);
        when(gridRepository.findAll()).thenReturn(List.of(grid));
        service = new GridServiceImpl(gridRepository, hexRepository,
                pincodeMappingRepository, hexVertexRepository,
                demandSnapshotRepository, assignmentRepository,
                resourceLoader, gridProperties, h3Core);
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
        UUID hexId = UUID.randomUUID();
        PincodeMapping pm = PincodeMapping.builder()
                .cityId(cityId).pincode("110001").hexId(hexId).serviceable(true).build();

        when(pincodeMappingRepository.findByCityIdAndPincode(cityId, "110001"))
                .thenReturn(Optional.of(pm));

        ServiceabilityResponse resp = service.checkServiceability(cityId, "110001");

        assertThat(resp.serviceable()).isTrue();
        assertThat(resp.hexId()).isEqualTo(hexId);
    }

    @Test
    void checkServiceability_unknownPincode_returnsFalse() {
        when(pincodeMappingRepository.findByCityIdAndPincode(cityId, "999999"))
                .thenReturn(Optional.empty());

        ServiceabilityResponse resp = service.checkServiceability(cityId, "999999");

        assertThat(resp.serviceable()).isFalse();
        assertThat(resp.hexId()).isNull();
    }

    @Test
    void checkServiceability_knownButNotServiceable_returnsFalse() {
        PincodeMapping pm = PincodeMapping.builder()
                .cityId(cityId).pincode("110002").hexId(null).serviceable(false).build();

        when(pincodeMappingRepository.findByCityIdAndPincode(cityId, "110002"))
                .thenReturn(Optional.of(pm));

        assertThat(service.checkServiceability(cityId, "110002").serviceable()).isFalse();
    }

    // ---- getTileAt ---------------------------------------------------------

    @Test
    void getTileAt_coordsMappedToHex_returnsHexResponse() {
        double lat = 28.6, lon = 77.2;
        when(h3Core.latLngToCell(lat, lon, 7)).thenReturn(TEST_HEX_INDEX);

        UUID hexId = UUID.randomUUID();
        Hex hex = Hex.builder().h3GridId(gridId).h3Index(TEST_HEX_INDEX).active(true).build();
        hex.setId(hexId);
        when(hexRepository.findByH3GridIdAndH3Index(gridId, TEST_HEX_INDEX))
                .thenReturn(Optional.of(hex));

        TileAtResponse resp = service.getTileAt(cityId, lat, lon);

        assertThat(resp.hexId()).isEqualTo(hexId);
        assertThat(resp.h3Index()).isEqualTo(Long.toHexString(TEST_HEX_INDEX));
        assertThat(resp.active()).isTrue();
    }

    @Test
    void getTileAt_inactiveHex_returnsInactiveFlag() {
        double lat = 28.6, lon = 77.2;
        when(h3Core.latLngToCell(lat, lon, 7)).thenReturn(TEST_HEX_INDEX);

        Hex hex = Hex.builder().h3GridId(gridId).h3Index(TEST_HEX_INDEX).active(false).build();
        hex.setId(UUID.randomUUID());
        when(hexRepository.findByH3GridIdAndH3Index(gridId, TEST_HEX_INDEX))
                .thenReturn(Optional.of(hex));

        TileAtResponse resp = service.getTileAt(cityId, lat, lon);

        assertThat(resp.active()).isFalse();
    }

    @Test
    void getTileAt_noHexInRepository_throwsIllegalArgument() {
        double lat = 28.6, lon = 77.2;
        when(h3Core.latLngToCell(lat, lon, 7)).thenReturn(TEST_HEX_INDEX);
        when(hexRepository.findByH3GridIdAndH3Index(gridId, TEST_HEX_INDEX))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTileAt(cityId, lat, lon))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No hex at");
    }
}
