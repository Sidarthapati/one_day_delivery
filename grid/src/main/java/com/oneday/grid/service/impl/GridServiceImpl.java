package com.oneday.grid.service.impl;

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
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    @Transactional
    public void initializeGrid(UUID cityId, String cityCode) {
        ServiceabilityConfig config = loadConfig(cityCode);
        int resolution = gridProperties.getH3().getResolution();

        List<ServiceabilityConfig.PincodeEntry> pincodes = config.serviceablePincodes();

        // Collect pincode cells, then buffer each with gridDisk(k=1) — all become active.
        Set<Long> activeCells = new HashSet<>();
        for (ServiceabilityConfig.PincodeEntry entry : pincodes) {
            long cell = h3Core.latLngToCell(entry.lat(), entry.lon(), resolution);
            activeCells.addAll(h3Core.gridDisk(cell, 1));
        }

        Grid grid = gridRepository.save(Grid.builder()
                .cityId(cityId)
                .h3Resolution(resolution)
                .build());

        List<Hex> hexes = activeCells.stream()
                .map(h3Index -> Hex.builder()
                        .h3GridId(grid.getId())
                        .h3Index(h3Index)
                        .active(true)
                        .build())
                .collect(Collectors.toList());
        hexes = hexRepository.saveAll(hexes);

        Map<Long, Hex> hexByIndex = hexes.stream()
                .collect(Collectors.toMap(Hex::getH3Index, h -> h));

        List<PincodeMapping> mappings = new ArrayList<>();
        for (ServiceabilityConfig.PincodeEntry entry : pincodes) {
            long cellIndex = h3Core.latLngToCell(entry.lat(), entry.lon(), resolution);
            Hex hex = hexByIndex.get(cellIndex);
            mappings.add(PincodeMapping.builder()
                    .cityId(cityId)
                    .pincode(entry.pincode())
                    .hexId(hex != null ? hex.getId() : null)
                    .serviceable(hex != null)
                    .build());
        }
        pincodeMappingRepository.saveAll(mappings);

        // Extract unique H3 vertices from all active hexes and persist them.
        Set<Long> vertexIndexSet = new HashSet<>();
        for (long cell : activeCells) {
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

    private ServiceabilityConfig loadConfig(String cityCode) {
        try {
            var resource = resourceLoader.getResource("classpath:serviceability/" + cityCode + ".yaml");
            return yamlMapper.readValue(resource.getInputStream(), ServiceabilityConfig.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("No serviceability config for cityCode=" + cityCode, e);
        }
    }
}
