package com.oneday.grid.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.oneday.grid.config.GridProperties;
import com.oneday.grid.config.ServiceabilityConfig;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaTileAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.GridVertex;
import com.oneday.grid.domain.PincodeMapping;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.domain.TileDemandSnapshot;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.dto.response.TileDetailResponse;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.GridVertexRepository;
import com.oneday.grid.repository.PincodeMappingRepository;
import com.oneday.grid.repository.TileDemandSnapshotRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.GridService;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GridServiceImpl implements GridService {

    private static final double KM_PER_DEGREE_LAT = 111.32;
    private static final double TILE_SIZE_KM = 2.0;

    private final GridRepository gridRepository;
    private final TileRepository tileRepository;
    private final PincodeMappingRepository pincodeMappingRepository;
    private final GridVertexRepository gridVertexRepository;
    private final TileDemandSnapshotRepository demandSnapshotRepository;
    private final DaTileAssignmentRepository assignmentRepository;
    private final ResourceLoader resourceLoader;
    private final GridProperties gridProperties;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Map<UUID, Grid> gridCache = new ConcurrentHashMap<>();

    GridServiceImpl(GridRepository gridRepository,
                    TileRepository tileRepository,
                    PincodeMappingRepository pincodeMappingRepository,
                    GridVertexRepository gridVertexRepository,
                    TileDemandSnapshotRepository demandSnapshotRepository,
                    DaTileAssignmentRepository assignmentRepository,
                    ResourceLoader resourceLoader,
                    GridProperties gridProperties) {
        this.gridRepository = gridRepository;
        this.tileRepository = tileRepository;
        this.pincodeMappingRepository = pincodeMappingRepository;
        this.gridVertexRepository = gridVertexRepository;
        this.demandSnapshotRepository = demandSnapshotRepository;
        this.assignmentRepository = assignmentRepository;
        this.resourceLoader = resourceLoader;
        this.gridProperties = gridProperties;
    }

    @PostConstruct
    void loadGridCache() {
        gridRepository.findAll().forEach(g -> gridCache.put(g.getCityId(), g));
    }

    @Override
    public Grid getGrid(UUID cityId) {
        Grid grid = gridCache.get(cityId);
        if (grid == null) {
            throw new IllegalArgumentException("No grid found for cityId=" + cityId);
        }
        return grid;
    }

    @Override
    public ServiceabilityResponse checkServiceability(UUID cityId, String pincode) {
        return pincodeMappingRepository.findByCityIdAndPincode(cityId, pincode)
                .map(pm -> new ServiceabilityResponse(cityId, pincode, pm.isServiceable(), pm.getTileId()))
                .orElse(new ServiceabilityResponse(cityId, pincode, false, null));
    }

    @Override
    public TileAtResponse getTileAt(UUID cityId, double lat, double lon) {
        Grid grid = getGrid(cityId);
        int row = (int) Math.floor((lat - grid.getOriginLat()) / grid.getTileDeltaLat());
        int col = (int) Math.floor((lon - grid.getOriginLon()) / grid.getTileDeltaLon());
        return tileRepository.findByGridIdAndRowIdxAndColIdx(grid.getId(), row, col)
                .map(t -> new TileAtResponse(t.getId(), t.getRowIdx(), t.getColIdx(), t.isActive()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No tile at row=" + row + ", col=" + col + " for cityId=" + cityId));
    }

    @Override
    public UUID resolveCityId(String cityCode) {
        UUID cityId = gridProperties.getCities().get(cityCode.toLowerCase());
        if (cityId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cityCode: " + cityCode);
        }
        return cityId;
    }

    @Override
    public List<TileDetailResponse> getTileDetails(UUID cityId, LocalDate date) {
        Grid grid = getGrid(cityId);
        List<Tile> tiles = tileRepository.findByGridId(grid.getId());

        Map<UUID, TileDemandSnapshot> snapshotByTile = demandSnapshotRepository
                .findBySnapshotDate(date).stream()
                .collect(Collectors.toMap(TileDemandSnapshot::getTileId, s -> s));

        return tiles.stream().map(t -> {
            double swLat = grid.getOriginLat() + t.getRowIdx() * grid.getTileDeltaLat();
            double swLon = grid.getOriginLon() + t.getColIdx() * grid.getTileDeltaLon();
            TileDemandSnapshot snap = snapshotByTile.get(t.getId());
            return new TileDetailResponse(
                    t.getId(), t.getRowIdx(), t.getColIdx(), t.isActive(),
                    swLat, swLon,
                    swLat + grid.getTileDeltaLat(), swLon + grid.getTileDeltaLon(),
                    snap != null ? snap.getDemandScoreOrders() : 0.0,
                    snap != null && snap.isBootstrapped()
            );
        }).toList();
    }

    @Override
    public List<GridVertexResponse> getVertices(UUID cityId) {
        Grid grid = getGrid(cityId);
        return gridVertexRepository.findByGridId(grid.getId()).stream()
                .map(v -> new GridVertexResponse(v.getId(), v.getRowIdx(), v.getColIdx(), v.getLat(), v.getLon()))
                .toList();
    }

    @Override
    @Transactional
    public void setTileActive(UUID tileId, boolean active) {
        Tile tile = tileRepository.findById(tileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tile not found: " + tileId));
        tile.setActive(active);
        tileRepository.save(tile);
    }

    @Override
    public List<AssignmentResponse> getActiveAssignments(UUID cityId, LocalDate date) {
        Grid grid = getGrid(cityId);
        Set<UUID> cityTileIds = tileRepository.findByGridId(grid.getId()).stream()
                .map(Tile::getId).collect(Collectors.toSet());
        return assignmentRepository
                .findByTileIdInAndValidDateAndStatus(cityTileIds, date, AssignmentStatus.ACTIVE)
                .stream()
                .map(a -> new AssignmentResponse(
                        a.getId(), a.getProposalId(), a.getDaId(), a.getTileId(),
                        a.getValidDate(), a.getNDasOnTile(), a.getStatus(),
                        a.getProposedAt(), a.getApprovedBy(), a.getApprovedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void initializeGrid(UUID cityId, String cityCode) {
        ServiceabilityConfig config = loadConfig(cityCode);

        double tileDeltaLat = TILE_SIZE_KM / KM_PER_DEGREE_LAT;
        double tileDeltaLon = TILE_SIZE_KM / (KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(config.centerLat())));

        List<ServiceabilityConfig.PincodeEntry> pincodes = config.serviceablePincodes();
        double latMin = pincodes.stream().mapToDouble(ServiceabilityConfig.PincodeEntry::lat).min().orElseThrow() - tileDeltaLat;
        double latMax = pincodes.stream().mapToDouble(ServiceabilityConfig.PincodeEntry::lat).max().orElseThrow() + tileDeltaLat;
        double lonMin = pincodes.stream().mapToDouble(ServiceabilityConfig.PincodeEntry::lon).min().orElseThrow() - tileDeltaLon;
        double lonMax = pincodes.stream().mapToDouble(ServiceabilityConfig.PincodeEntry::lon).max().orElseThrow() + tileDeltaLon;

        int nRows = (int) Math.ceil((latMax - latMin) / tileDeltaLat);
        int nCols = (int) Math.ceil((lonMax - lonMin) / tileDeltaLon);

        Grid grid = gridRepository.save(Grid.builder()
                .cityId(cityId)
                .originLat(latMin)
                .originLon(lonMin)
                .tileDeltaLat(tileDeltaLat)
                .tileDeltaLon(tileDeltaLon)
                .build());

        List<Tile> tiles = new ArrayList<>(nRows * nCols);
        for (int r = 0; r < nRows; r++) {
            for (int c = 0; c < nCols; c++) {
                tiles.add(Tile.builder().gridId(grid.getId()).rowIdx(r).colIdx(c).active(false).build());
            }
        }
        tiles = tileRepository.saveAll(tiles);

        Map<String, Tile> tileIndex = new HashMap<>();
        for (Tile t : tiles) {
            tileIndex.put(t.getRowIdx() + "," + t.getColIdx(), t);
        }

        // Map pincodes to tiles; track which tiles are directly pincode-activated.
        List<PincodeMapping> mappings = new ArrayList<>();
        Set<Tile> pincodeActivatedTiles = new HashSet<>();
        for (ServiceabilityConfig.PincodeEntry entry : pincodes) {
            int row = (int) Math.floor((entry.lat() - latMin) / tileDeltaLat);
            int col = (int) Math.floor((entry.lon() - lonMin) / tileDeltaLon);
            Tile tile = tileIndex.get(row + "," + col);
            if (tile != null) {
                tile.setActive(true);
                pincodeActivatedTiles.add(tile);
                mappings.add(PincodeMapping.builder()
                        .cityId(cityId).pincode(entry.pincode())
                        .tileId(tile.getId()).serviceable(true).build());
            } else {
                mappings.add(PincodeMapping.builder()
                        .cityId(cityId).pincode(entry.pincode())
                        .tileId(null).serviceable(false).build());
            }
        }

        // Activate N/S/E/W neighbors of every pincode-mapped tile (1-tile geometric buffer, no chain).
        // Handles pincodes whose coverage area bleeds into an adjacent 2×2 km tile.
        int[][] neighborOffsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (Tile pincodeTile : pincodeActivatedTiles) {
            int r = pincodeTile.getRowIdx(), c = pincodeTile.getColIdx();
            for (int[] offset : neighborOffsets) {
                Tile neighbor = tileIndex.get((r + offset[0]) + "," + (c + offset[1]));
                if (neighbor != null) neighbor.setActive(true);
            }
        }

        tileRepository.saveAll(tiles);
        pincodeMappingRepository.saveAll(mappings);

        List<GridVertex> vertices = new ArrayList<>((nRows + 1) * (nCols + 1));
        for (int r = 0; r <= nRows; r++) {
            for (int c = 0; c <= nCols; c++) {
                vertices.add(GridVertex.builder()
                        .gridId(grid.getId()).rowIdx(r).colIdx(c)
                        .lat(latMin + r * tileDeltaLat)
                        .lon(lonMin + c * tileDeltaLon)
                        .build());
            }
        }
        gridVertexRepository.saveAll(vertices);

        gridCache.put(cityId, grid);
    }

    private ServiceabilityConfig loadConfig(String cityCode) {
        try {
            var resource = resourceLoader.getResource("classpath:serviceability/" + cityCode + ".yaml");
            return yamlMapper.readValue(resource.getInputStream(), ServiceabilityConfig.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("No serviceability config for cityCode=" + cityCode, e);
        }
    }
}
