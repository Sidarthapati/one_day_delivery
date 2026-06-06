package com.oneday.grid.api;

import com.oneday.grid.dto.request.ReplanRequest;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.dto.response.TileAtResponse;
import com.oneday.grid.dto.response.TileDetailResponse;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.service.GridReplanService;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/grid")
public class GridController {

    private final GridService gridService;
    private final GridReplanService gridReplanService;
    private final IntradayLoadScoreService loadScoreService;

    GridController(GridService gridService,
                   GridReplanService gridReplanService,
                   IntradayLoadScoreService loadScoreService) {
        this.gridService = gridService;
        this.gridReplanService = gridReplanService;
        this.loadScoreService = loadScoreService;
    }

    // ── Tiles ──────────────────────────────────────────────────────────────────

    @GetMapping("/{cityCode}/tiles")
    public List<TileDetailResponse> getTiles(
            @PathVariable String cityCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID cityId = gridService.resolveCityId(cityCode);
        return gridService.getTileDetails(cityId, date != null ? date : LocalDate.now());
    }

    @PatchMapping("/{cityCode}/tiles/{tileId}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setTileActive(
            @PathVariable String cityCode,
            @PathVariable UUID tileId,
            @RequestParam boolean active) {
        gridService.resolveCityId(cityCode); // validates city exists
        gridService.setTileActive(tileId, active);
    }

    @GetMapping("/{cityCode}/tiles/{tileId}/load-score")
    public TileLoadScoreResponse getTileLoadScore(
            @PathVariable String cityCode,
            @PathVariable UUID tileId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        gridService.resolveCityId(cityCode);
        return loadScoreService.getLoadScore(tileId, date != null ? date : LocalDate.now());
    }

    // ── Vertices (map grid-lines) ──────────────────────────────────────────────

    @GetMapping("/{cityCode}/vertices")
    public List<GridVertexResponse> getVertices(@PathVariable String cityCode) {
        UUID cityId = gridService.resolveCityId(cityCode);
        return gridService.getVertices(cityId);
    }

    // ── Assignments ───────────────────────────────────────────────────────────

    @GetMapping("/{cityCode}/assignments")
    public List<AssignmentResponse> getActiveAssignments(
            @PathVariable String cityCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID cityId = gridService.resolveCityId(cityCode);
        return gridService.getActiveAssignments(cityId, date != null ? date : LocalDate.now());
    }

    // ── Lookup helpers ────────────────────────────────────────────────────────

    @GetMapping("/{cityCode}/serviceability")
    public ServiceabilityResponse checkServiceability(
            @PathVariable String cityCode,
            @RequestParam String pincode) {
        UUID cityId = gridService.resolveCityId(cityCode);
        return gridService.checkServiceability(cityId, pincode);
    }

    @GetMapping("/{cityCode}/tile-at")
    public TileAtResponse getTileAt(
            @PathVariable String cityCode,
            @RequestParam double lat,
            @RequestParam double lon) {
        UUID cityId = gridService.resolveCityId(cityCode);
        return gridService.getTileAt(cityId, lat, lon);
    }

    // City-agnostic serviceability for a raw point — the booking map UI calls this on
    // pin drop without needing to know which city the point lands in.
    @GetMapping("/serviceable-at")
    public ServiceableAtResponse serviceableAt(
            @RequestParam double lat,
            @RequestParam double lon) {
        return gridService.serviceableAt(lat, lon);
    }

    // ── Replan ────────────────────────────────────────────────────────────────

    @PostMapping("/{cityCode}/replan")
    @ResponseStatus(HttpStatus.CREATED)
    public ProposalResponse replan(
            @PathVariable String cityCode,
            @RequestBody ReplanRequest request) {
        UUID cityId = gridService.resolveCityId(cityCode);
        LocalDate date = request.date() != null ? request.date() : LocalDate.now().plusDays(1);
        List<UUID> daIds = request.daIds() != null ? request.daIds() : List.of();
        return gridReplanService.replan(cityId, date, daIds);
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    @PostMapping("/admin/init")
    @ResponseStatus(HttpStatus.CREATED)
    public void initGrid(@RequestParam String cityCode) {
        UUID cityId = gridService.resolveCityId(cityCode);
        gridService.initializeGrid(cityId, cityCode);
    }
}
