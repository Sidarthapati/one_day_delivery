package com.oneday.grid.api;

import com.oneday.grid.dto.request.ApproveRequest;
import com.oneday.grid.dto.request.IntradayReassignmentRequest;
import com.oneday.grid.dto.request.ProposalRejectRequest;
import com.oneday.grid.dto.request.RegionEditRequest;
import com.oneday.grid.dto.request.TileShareRequest;
import com.oneday.grid.dto.response.IntradayReassignmentResponse;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.dto.response.TileShareResponse;
import com.oneday.grid.service.GridService;
import com.oneday.grid.service.ProposalService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private final ProposalService proposalService;
    private final GridService gridService;

    ProposalController(ProposalService proposalService, GridService gridService) {
        this.proposalService = proposalService;
        this.gridService = gridService;
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @GetMapping("/{proposalId}")
    public ProposalResponse getProposal(@PathVariable UUID proposalId) {
        return proposalService.getProposal(proposalId);
    }

    @GetMapping
    public List<ProposalResponse> getProposals(
            @RequestParam String cityCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID cityId = gridService.resolveCityId(cityCode);
        return proposalService.getProposals(cityId, date);
    }

    // ── Nightly proposal lifecycle ────────────────────────────────────────────

    @PostMapping("/{proposalId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID proposalId, @RequestBody ApproveRequest request) {
        proposalService.approve(proposalId, request.reviewerId());
    }

    @PostMapping("/{proposalId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID proposalId, @RequestBody ProposalRejectRequest request) {
        proposalService.reject(proposalId, request.reviewerId(), request.notes());
    }

    // Scenario A: edit a DA's region inside a PROPOSED proposal before it is approved.
    @PutMapping("/{proposalId}/regions/{daId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void editRegion(
            @PathVariable UUID proposalId,
            @PathVariable UUID daId,
            @RequestBody RegionEditRequest request) {
        proposalService.editRegionInProposal(proposalId, daId, request.newTileIds(), request.reviewerId());
    }

    // ── Intraday reassignment (Scenario B) ───────────────────────────────────

    @PostMapping("/intraday-reassignment")
    @ResponseStatus(HttpStatus.CREATED)
    public IntradayReassignmentResponse requestReassignment(
            @RequestBody IntradayReassignmentRequest request) {
        return proposalService.requestIntradayReassignment(
                request.cityId(), request.fromDaId(), request.toDaId(),
                request.tileIdsToMove(), request.requestedBy());
    }

    @PostMapping("/{proposalId}/approve-reassignment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveReassignment(
            @PathVariable UUID proposalId,
            @RequestBody ApproveRequest request) {
        proposalService.approveIntradayReassignment(proposalId, request.reviewerId());
    }

    // ── Tile share ────────────────────────────────────────────────────────────

    @PostMapping("/tile-share")
    @ResponseStatus(HttpStatus.CREATED)
    public TileShareResponse requestTileShare(@RequestBody TileShareRequest request) {
        return proposalService.requestTileShare(
                request.cityId(), request.daId(), request.tileId(), request.requestedBy());
    }

    @PostMapping("/{proposalId}/approve-tile-share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approveTileShare(
            @PathVariable UUID proposalId,
            @RequestBody ApproveRequest request) {
        proposalService.approveTileShare(proposalId, request.reviewerId());
    }
}
