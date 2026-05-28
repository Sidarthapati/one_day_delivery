package com.oneday.grid.api;

import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.domain.HexDemandSnapshot;
import com.oneday.grid.dto.request.DemoHexDemandRequest;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.HexDemandSnapshotRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.service.GridService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final HexRepository hexRepository;
    private final HexDemandSnapshotRepository demandSnapshotRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final GridService gridService;

    DemoController(HexRepository hexRepository,
                   HexDemandSnapshotRepository demandSnapshotRepository,
                   DaHexAssignmentRepository assignmentRepository,
                   GridService gridService) {
        this.hexRepository = hexRepository;
        this.demandSnapshotRepository = demandSnapshotRepository;
        this.assignmentRepository = assignmentRepository;
        this.gridService = gridService;
    }

    public record HexDetail(
            UUID hexId,
            String h3Index,
            boolean active,
            double demandScoreMinutes,
            double demandScoreOrders,
            double serviceTimeMin,
            double interStopTravelMin,
            boolean bootstrapped,
            UUID assignedDaId
    ) {}

    @GetMapping("/hexes/{hexId}/detail")
    public HexDetail getHexDetail(
            @PathVariable UUID hexId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        Hex hex = hexRepository.findById(hexId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hex not found: " + hexId));

        HexDemandSnapshot snap = demandSnapshotRepository
                .findByHexIdAndSnapshotDate(hexId, date)
                .orElse(null);

        List<DaHexAssignment> assignments = assignmentRepository
                .findByHexIdAndValidDateAndStatus(hexId, date, AssignmentStatus.ACTIVE);
        UUID assignedDaId = assignments.isEmpty() ? null : assignments.get(0).getDaId();

        return new HexDetail(
                hex.getId(),
                Long.toHexString(hex.getH3Index()),
                hex.isActive(),
                snap != null ? snap.getDemandScoreMinutes() : 0.0,
                snap != null ? snap.getDemandScoreOrders() : 0.0,
                snap != null ? snap.getServiceTimeMin() : 0.0,
                snap != null ? snap.getInterStopTravelMin() : 0.0,
                snap != null && snap.isBootstrapped(),
                assignedDaId
        );
    }

    @PutMapping("/hexes/{hexId}/demand")
    public ResponseEntity<Void> updateHexDemand(
            @PathVariable UUID hexId,
            @RequestBody DemoHexDemandRequest request) {

        LocalDate today = LocalDate.now();
        hexRepository.findById(hexId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hex not found: " + hexId));

        demandSnapshotRepository.findByHexIdAndSnapshotDate(hexId, today)
                .ifPresent(demandSnapshotRepository::delete);

        HexDemandSnapshot newSnap = HexDemandSnapshot.builder()
                .hexId(hexId)
                .snapshotDate(today)
                .histAvgOrders(0.0)
                .currentOrders(0)
                .demandScoreOrders(0.0)
                .serviceTimeMin(0.0)
                .interStopTravelMin(0.0)
                .orderEngagedMin(request.demandScoreMinutes())
                .demandScoreMinutes(request.demandScoreMinutes())
                .bootstrapped(false)
                .build();
        demandSnapshotRepository.save(newSnap);

        return ResponseEntity.noContent().build();
    }

    public record SeedResult(int hexesSeeded, double minMinutes, double maxMinutes) {}

    @PostMapping("/seed")
    @Transactional
    public SeedResult seedDemand(
            @RequestParam String cityCode,
            @RequestParam(defaultValue = "30") double minMinutes,
            @RequestParam(defaultValue = "150") double maxMinutes) {

        UUID cityId = gridService.resolveCityId(cityCode);
        Grid grid = gridService.getGrid(cityId);
        List<Hex> hexes = hexRepository.findByH3GridId(grid.getId());
        if (hexes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No hexes found for cityCode=" + cityCode + ". Initialize the grid first.");
        }

        LocalDate today = LocalDate.now();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        List<UUID> hexIds = hexes.stream().map(Hex::getId).toList();
        demandSnapshotRepository.deleteByHexIdInAndSnapshotDate(hexIds, today);

        // Two-uniform-sum trick: result peaks at midpoint, tails off at min/max.
        List<HexDemandSnapshot> snapshots = new ArrayList<>(hexes.size());
        for (Hex hex : hexes) {
            // Two-uniform sum: result in [0,2], shift/scale to [min,max]
            double u = (rng.nextDouble() + rng.nextDouble()) / 2.0; // in [0,1], peak at 0.5
            double demandMin = minMinutes + u * (maxMinutes - minMinutes);

            double orders             = demandMin / 15.0;
            double serviceTimeMin     = orders * 5.0;
            double interStopTravelMin = orders * 3.0;

            snapshots.add(HexDemandSnapshot.builder()
                    .hexId(hex.getId())
                    .snapshotDate(today)
                    .histAvgOrders(orders)
                    .currentOrders((int) Math.round(orders))
                    .demandScoreOrders(orders)
                    .serviceTimeMin(serviceTimeMin)
                    .interStopTravelMin(interStopTravelMin)
                    .orderEngagedMin(demandMin)
                    .demandScoreMinutes(demandMin)
                    .bootstrapped(false)
                    .build());
        }
        demandSnapshotRepository.saveAll(snapshots);

        return new SeedResult(snapshots.size(), minMinutes, maxMinutes);
    }
}
