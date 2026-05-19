package com.oneday.grid.service;

import com.oneday.grid.dto.response.IntradayReassignmentResponse;
import com.oneday.grid.dto.response.ProposalResponse;
import com.oneday.grid.dto.response.TileShareResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProposalService {

    ProposalResponse getProposal(UUID proposalId);

    List<ProposalResponse> getProposals(UUID cityId, LocalDate date);

    // Nightly proposal lifecycle
    void approve(UUID proposalId, UUID reviewerId);

    void reject(UUID proposalId, UUID reviewerId, String notes);

    // Scenario A: edit a DA's region inside an existing PROPOSED (pre-approval) proposal.
    // newTileIds is the complete replacement tile set for this DA.
    void editRegionInProposal(UUID proposalId, UUID daId, List<UUID> newTileIds, UUID reviewerId);

    // Scenario B: move tiles from one DA to another on an already-ACTIVE plan.
    // Creates a new INTRADAY_OVERRIDE proposal that itself requires approval.
    IntradayReassignmentResponse requestIntradayReassignment(UUID cityId, UUID fromDaId, UUID toDaId,
                                                             List<UUID> tileIdsToMove, UUID requestedBy);

    void approveIntradayReassignment(UUID proposalId, UUID reviewerId);

    // Tile share: add a second DA to a tile without removing the existing DA.
    TileShareResponse requestTileShare(UUID cityId, UUID daId, UUID tileId, UUID requestedBy);

    void approveTileShare(UUID proposalId, UUID reviewerId);
}
