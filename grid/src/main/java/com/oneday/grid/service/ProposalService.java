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
    // newHexIds is the complete replacement hex set for this DA.
    void editRegionInProposal(UUID proposalId, UUID daId, List<UUID> newHexIds, UUID reviewerId);

    // Scenario B: move hexes from one DA to another on an already-ACTIVE plan.
    // Creates a new INTRADAY_OVERRIDE proposal that itself requires approval.
    IntradayReassignmentResponse requestIntradayReassignment(UUID cityId, UUID fromDaId, UUID toDaId,
                                                             List<UUID> hexIdsToMove, UUID requestedBy);

    void approveIntradayReassignment(UUID proposalId, UUID reviewerId);

    // Hex share: add a second DA to a hex without removing the existing DA.
    TileShareResponse requestTileShare(UUID cityId, UUID daId, UUID hexId, UUID requestedBy);

    void approveTileShare(UUID proposalId, UUID reviewerId);
}
