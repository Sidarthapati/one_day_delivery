package com.oneday.grid.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.oneday.grid.config.GridProperties;
import com.oneday.grid.config.ServiceabilityConfig;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.domain.HexVertex;
import com.oneday.grid.domain.PincodeMapping;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.dto.response.TerritoryHexResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.dto.response.TileDetailResponse;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexDemandSnapshotRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.repository.HexVertexRepository;
import com.oneday.grid.repository.PincodeMappingRepository;
import com.oneday.grid.service.GridService;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GridServiceImpl implements GridService {

    private final GridRepository gridRepository;
    private final HexRepository hexRepository;
    private final PincodeMappingRepository pincodeMappingRepository;
    private final HexVertexRepository hexVertexRepository;
    private final HexDemandSnapshotRepository demandSnapshotRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final ResourceLoader resourceLoader;
    private final GridProperties gridProperties;
    private final H3Core h3Core;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Map<UUID, Grid> gridCache = new ConcurrentHashMap<>();

    GridServiceImpl(GridRepository gridRepository,
                    HexRepository hexRepository,
                    PincodeMappingRepository pincodeMappingRepository,
                    HexVertexRepository hexVertexRepository,
                    HexDemandSnapshotRepository demandSnapshotRepository,
                    DaHexAssignmentRepository assignmentRepository,
                    ResourceLoader resourceLoader,
                    GridProperties gridProperties,
                    H3Core h3Core) {
        this.gridRepository = gridRepository;
        this.hexRepository = hexRepository;
        this.pincodeMappingRepository = pincodeMappingRepository;
        this.hexVertexRepository = hexVertexRepository;
        this.demandSnapshotRepository = demandSnapshotRepository;
        this.assignmentRepository = assignmentRepository;
        this.resourceLoader = resourceLoader;
        this.gridProperties = gridProperties;
        this.h3Core = h3Core;
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
                .map(pm -> new ServiceabilityResponse(cityId, pincode, pm.isServiceable(), pm.getHexId()))
                .orElse(new ServiceabilityResponse(cityId, pincode, false, null));
    }

    @Override
    public TileAtResponse getTileAt(UUID cityId, double lat, double lon) {
        Grid grid = getGrid(cityId);
        long h3Index = h3Core.latLngToCell(lat, lon, grid.getH3Resolution());
        return hexRepository.findByH3GridIdAndH3Index(grid.getId(), h3Index)
                .map(hex -> new TileAtResponse(hex.getId(), Long.toHexString(hex.getH3Index()), hex.isActive()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No hex at " + Long.toHexString(h3Index) + " for cityId=" + cityId));
    }

    @Override
    public ServiceableAtResponse serviceableAt(double lat, double lon) {
        // Every city grid shares one H3 resolution and H3 cells are globally unique, so a WGS84
        // point maps to exactly one hex across all grids. Resolve it in a single indexed lookup
        // instead of scanning each city (the old loop did up to one findByCityId + one hex query
        // per configured city, on every call). Grids are cached at startup and immutable intraday.
        long h3Index = h3Core.latLngToCell(lat, lon, gridProperties.getH3().getResolution());
        Optional<Hex> hexOpt = hexRepository.findByH3Index(h3Index);
        if (hexOpt.isEmpty()) {
            return new ServiceableAtResponse(false, null, null, null, null);
        }
        Hex hex = hexOpt.get();
        UUID cityId = cityIdForGrid(hex.getH3GridId());
        String cityCode = cityCodeForCity(cityId);
        if (cityId == null || cityCode == null) {
            // Hex exists but its grid is not a configured serviceable city — treat as not serviceable.
            return new ServiceableAtResponse(false, null, null, null, null);
        }
        return new ServiceableAtResponse(
                hex.isActive(), cityCode, cityId, hex.getId(), Long.toHexString(hex.getH3Index()));
    }

    /** Resolves a grid's own id to its cityId via the in-memory grid cache (no DB hit). */
    private UUID cityIdForGrid(UUID gridId) {
        for (Grid g : gridCache.values()) {
            if (g.getId().equals(gridId)) {
                return g.getCityId();
            }
        }
        return null;
    }

    /** Reverse of the configured cityCode→cityId map. */
    private String cityCodeForCity(UUID cityId) {
        if (cityId == null) {
            return null;
        }
        for (Map.Entry<String, UUID> e : gridProperties.getCities().entrySet()) {
            if (cityId.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
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
        List<Hex> hexes = hexRepository.findByH3GridId(grid.getId());

        Map<UUID, HexDemandSnapshot> snapshotByHex = demandSnapshotRepository
                .findBySnapshotDate(date).stream()
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId, s -> s));

        return hexes.stream().map(hex -> {
            LatLng center = h3Core.cellToLatLng(hex.getH3Index());
            HexDemandSnapshot snap = snapshotByHex.get(hex.getId());
            return new TileDetailResponse(
                    hex.getId(),
                    Long.toHexString(hex.getH3Index()),
                    hex.isActive(),
                    center.lat,
                    center.lng,
                    snap != null ? snap.getDemandScoreOrders() : 0.0,
                    snap != null ? snap.getDemandScoreMinutes() : 0.0,
                    snap != null && snap.isBootstrapped()
            );
        }).toList();
    }

    @Override
    public List<GridVertexResponse> getVertices(UUID cityId) {
        Grid grid = getGrid(cityId);
        return hexVertexRepository.findByH3GridId(grid.getId()).stream()
                .map(v -> new GridVertexResponse(v.getId(), v.getLat(), v.getLon()))
                .toList();
    }

    @Override
    @Transactional
    public void setTileActive(UUID hexId, boolean active) {
        Hex hex = hexRepository.findById(hexId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hex not found: " + hexId));
        hex.setActive(active);
        hexRepository.save(hex);
    }

    @Override
    public List<AssignmentResponse> getActiveAssignments(UUID cityId, LocalDate date) {
        Grid grid = getGrid(cityId);
        Set<UUID> cityHexIds = hexRepository.findByH3GridId(grid.getId()).stream()
                .map(Hex::getId).collect(Collectors.toSet());
        return assignmentRepository
                .findByHexIdInAndValidDateAndStatus(cityHexIds, date, AssignmentStatus.ACTIVE)
                .stream()
                .map(a -> new AssignmentResponse(
                        a.getId(), a.getProposalId(), a.getDaId(), a.getHexId(),
                        a.getValidDate(), a.getNDasOnHex(), a.getStatus(),
                        a.getProposedAt(), a.getApprovedBy(), a.getApprovedAt()))
                .toList();
    }

    @Override
    public List<DaTerritoryResponse> getDaTerritories(UUID cityId, LocalDate date) {
        Grid grid = getGrid(cityId);

        // City's hexes, by id — also the scope filter for assignments.
        Map<UUID, Hex> hexById = hexRepository.findByH3GridId(grid.getId()).stream()
                .collect(Collectors.toMap(Hex::getId, h -> h));

        // The date's demand snapshot, by hex (absent → 0 demand).
        Map<UUID, HexDemandSnapshot> snapshotByHex = demandSnapshotRepository
                .findBySnapshotDate(date).stream()
                .collect(Collectors.toMap(HexDemandSnapshot::getHexId, s -> s));

        // Vertex rows for this grid, keyed by their globally-unique H3 vertex index, so a hex's
        // h3Core.cellToVertexes(...) corners resolve to the persisted (deduped) GridVertexResponse.
        Map<Long, GridVertexResponse> vertexByH3Index = hexVertexRepository.findByH3GridId(grid.getId())
                .stream()
                .collect(Collectors.toMap(
                        HexVertex::getH3VertexIndex,
                        v -> new GridVertexResponse(v.getId(), v.getLat(), v.getLon())));

        // ACTIVE assignments for the date, scoped to this city's hexes, grouped per DA.
        Map<UUID, List<DaHexAssignment>> byDa = assignmentRepository
                .findByHexIdInAndValidDateAndStatus(hexById.keySet(), date, AssignmentStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(DaHexAssignment::getDaId));

        List<DaTerritoryResponse> territories = new ArrayList<>(byDa.size());
        for (Map.Entry<UUID, List<DaHexAssignment>> entry : byDa.entrySet()) {
            List<TerritoryHexResponse> hexes = new ArrayList<>(entry.getValue().size());
            for (DaHexAssignment a : entry.getValue()) {
                Hex hex = hexById.get(a.getHexId());
                if (hex == null) continue; // assignment outside this city's grid — skip defensively

                List<GridVertexResponse> vertices = new ArrayList<>(6);
                for (long vIdx : h3Core.cellToVertexes(hex.getH3Index())) {
                    GridVertexResponse v = vertexByH3Index.get(vIdx);
                    if (v != null) vertices.add(v);
                }

                HexDemandSnapshot snap = snapshotByHex.get(hex.getId());
                hexes.add(new TerritoryHexResponse(
                        hex.getId(),
                        hex.getH3Index(),
                        snap != null ? snap.getDemandScoreOrders() : 0.0,
                        snap != null ? snap.getServiceTimeMin() : 0.0,
                        vertices));
            }
            territories.add(new DaTerritoryResponse(entry.getKey(), hexes));
        }
        return territories;
    }

    @Override
    @Transactional
    public void initializeGrid(UUID cityId, String cityCode) {
        if (gridRepository.findByCityId(cityId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grid already exists for cityId=" + cityId);
        }

        int resolution = gridProperties.getH3().getResolution();
        List<Long> cells = polyfillCity(cityCode, resolution);
        if (cells.isEmpty()) {
            throw new IllegalStateException("polyfill returned 0 cells for cityCode=" + cityCode);
        }

        Grid grid = gridRepository.save(Grid.builder()
                .cityId(cityId)
                .h3Resolution(resolution)
                .build());

        List<Hex> hexes = cells.stream()
                .map(h3Index -> Hex.builder()
                        .h3GridId(grid.getId())
                        .h3Index(h3Index)
                        .active(true)
                        .build())
                .collect(Collectors.toList());
        hexes = hexRepository.saveAll(hexes);

        Map<Long, Hex> hexByIndex = hexes.stream()
                .collect(Collectors.toMap(Hex::getH3Index, h -> h));

        // Map pincodes to their hexes for serviceability lookups (catalogue is optional).
        List<PincodeMapping> mappings = loadPincodeCatalogue(cityCode).serviceablePincodes().stream()
                .map(entry -> {
                    long cellIndex = h3Core.latLngToCell(entry.lat(), entry.lon(), resolution);
                    Hex hex = hexByIndex.get(cellIndex);
                    return PincodeMapping.builder()
                            .cityId(cityId)
                            .pincode(entry.pincode())
                            .hexId(hex != null ? hex.getId() : null)
                            .serviceable(hex != null)
                            .build();
                })
                .collect(Collectors.toList());
        pincodeMappingRepository.saveAll(mappings);

        // Extract unique H3 vertices for map rendering.
        Set<Long> vertexIndexSet = new HashSet<>();
        for (long cell : cells) {
            vertexIndexSet.addAll(h3Core.cellToVertexes(cell));
        }
        List<HexVertex> vertices = vertexIndexSet.stream()
                .map(vIdx -> {
                    LatLng latLng = h3Core.vertexToLatLng(vIdx);
                    return HexVertex.builder()
                            .h3GridId(grid.getId())
                            .h3VertexIndex(vIdx)
                            .lat(latLng.lat)
                            .lon(latLng.lng)
                            .build();
                })
                .toList();
        hexVertexRepository.saveAll(vertices);

        gridCache.put(cityId, grid);
    }

    private List<Long> polyfillCity(String cityCode, int resolution) {
        try {
            var resource = resourceLoader.getResource("classpath:serviceability/" + cityCode + ".geojson");
            JsonNode root = new ObjectMapper().readTree(resource.getInputStream());
            JsonNode rings = root.get("coordinates");

            List<LatLng> boundary = new ArrayList<>();
            for (JsonNode pt : rings.get(0)) {
                boundary.add(new LatLng(pt.get(1).asDouble(), pt.get(0).asDouble()));
            }

            List<List<LatLng>> holes = new ArrayList<>();
            for (int i = 1; i < rings.size(); i++) {
                List<LatLng> hole = new ArrayList<>();
                for (JsonNode pt : rings.get(i)) {
                    hole.add(new LatLng(pt.get(1).asDouble(), pt.get(0).asDouble()));
                }
                holes.add(hole);
            }

            return h3Core.polygonToCells(boundary, holes.isEmpty() ? Collections.emptyList() : holes, resolution);
        } catch (IOException e) {
            throw new IllegalArgumentException("No GeoJSON boundary for cityCode=" + cityCode, e);
        }
    }

    private ServiceabilityConfig loadPincodeCatalogue(String cityCode) {
        try {
            var resource = resourceLoader.getResource("classpath:serviceability/" + cityCode + ".yaml");
            return yamlMapper.readValue(resource.getInputStream(), ServiceabilityConfig.class);
        } catch (IOException e) {
            return new ServiceabilityConfig(null, null, List.of());
        }
    }
}
