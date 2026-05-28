package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentProposalRegion;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.HexTravelTime;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.dto.response.IntradayReassignmentResponse;
import com.oneday.grid.dto.response.TileShareResponse;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexTravelTimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProposalServiceImplTest {

    @Mock AssignmentProposalRepository proposalRepository;
    @Mock AssignmentProposalRegionRepository regionRepository;
    @Mock DaHexAssignmentRepository assignmentRepository;
    @Mock GridRepository gridRepository;
    @Mock HexTravelTimeRepository travelTimeRepository;
    @Mock Grid grid;

    GridProperties properties = new GridProperties();
    ProposalServiceImpl service;

    private final UUID cityId     = UUID.randomUUID();
    private final UUID gridId     = UUID.randomUUID();
    private final UUID proposalId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final LocalDate date  = LocalDate.now();

    @BeforeEach
    void setUp() {
        lenient().when(grid.getId()).thenReturn(gridId);
        service = new ProposalServiceImpl(proposalRepository, regionRepository,
                assignmentRepository, gridRepository, travelTimeRepository, properties);
    }

    // ---- helpers ----------------------------------------------------------

    private AssignmentProposal proposedProposal() {
        AssignmentProposal p = AssignmentProposal.builder()
                .cityId(cityId).validForDate(date)
                .status(ProposalStatus.PROPOSED)
                .proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.CP_SAT)
                .adjacencySource(AdjacencySource.OSRM)
                .totalDas(1).coveragePct(100.0).understaffedHexIds("[]").build();
        p.setId(proposalId);
        p.setProposedAt(Instant.now());
        return p;
    }

    private DaHexAssignment proposedAssignment(UUID proposalId, UUID daId, UUID hexId) {
        DaHexAssignment a = DaHexAssignment.builder()
                .proposalId(proposalId).daId(daId).hexId(hexId)
                .validDate(date).status(AssignmentStatus.PROPOSED).build();
        a.setId(UUID.randomUUID());
        a.setProposedAt(Instant.now());
        return a;
    }

    private DaHexAssignment activeAssignment(UUID proposalId, UUID daId, UUID hexId) {
        DaHexAssignment a = proposedAssignment(proposalId, daId, hexId);
        a.setStatus(AssignmentStatus.ACTIVE);
        return a;
    }

    private Grid grid() {
        return grid;
    }

    // ---- approve ----------------------------------------------------------

    @Test
    void approve_proposedProposal_setsApprovedAndActivatesAssignments() {
        UUID daId   = UUID.randomUUID();
        UUID hexId  = UUID.randomUUID();
        AssignmentProposal proposal = proposedProposal();
        DaHexAssignment assignment = proposedAssignment(proposalId, daId, hexId);

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(proposalRepository.findByCityIdAndValidForDate(cityId, date))
                .thenReturn(List.of(proposal));
        when(assignmentRepository.findByProposalId(proposalId)).thenReturn(List.of(assignment));
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(proposalId, reviewerId);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(proposal.getReviewedBy()).isEqualTo(reviewerId);
        assertThat(proposal.getReviewedAt()).isNotNull();
        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.APPROVED);
        assertThat(assignment.getApprovedBy()).isEqualTo(reviewerId);
        assertThat(assignment.getApprovedAt()).isNotNull();
    }

    @Test
    void approve_supersedingExistingApprovedProposal_setsOldToSuperseded() {
        UUID existingProposalId = UUID.randomUUID();
        AssignmentProposal existing = proposedProposal();
        existing.setId(existingProposalId);
        existing.setStatus(ProposalStatus.APPROVED);
        AssignmentProposal toApprove = proposedProposal();

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(toApprove));
        when(proposalRepository.findByCityIdAndValidForDate(cityId, date))
                .thenReturn(List.of(existing, toApprove));
        when(assignmentRepository.findByProposalId(any())).thenReturn(List.of());
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(proposalId, reviewerId);

        assertThat(existing.getStatus()).isEqualTo(ProposalStatus.SUPERSEDED);
        assertThat(toApprove.getStatus()).isEqualTo(ProposalStatus.APPROVED);
    }

    @Test
    void approve_nonProposedStatus_throwsIllegalState() {
        AssignmentProposal proposal = proposedProposal();
        proposal.setStatus(ProposalStatus.APPROVED);

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.approve(proposalId, reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot approve");
    }

    @Test
    void approve_proposalNotFound_throwsIllegalArgument() {
        when(proposalRepository.findById(proposalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(proposalId, reviewerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proposal not found");
    }

    // ---- reject -----------------------------------------------------------

    @Test
    void reject_proposedProposal_setsRejectedWithNotes() {
        AssignmentProposal proposal = proposedProposal();

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(proposalId, reviewerId, "territory imbalance");

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(proposal.getReviewedBy()).isEqualTo(reviewerId);
        assertThat(proposal.getNotes()).isEqualTo("territory imbalance");
    }

    @Test
    void reject_alreadyRejected_throwsIllegalState() {
        AssignmentProposal proposal = proposedProposal();
        proposal.setStatus(ProposalStatus.REJECTED);

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.reject(proposalId, reviewerId, "again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reject");
    }

    // ---- editRegionInProposal (Scenario A) --------------------------------

    @Test
    void editRegionInProposal_contiguousTiles_supersedesOldAndInsertsNew() {
        UUID daId    = UUID.randomUUID();
        UUID oldHex  = UUID.randomUUID();
        UUID newH1   = UUID.randomUUID();
        UUID newH2   = UUID.randomUUID();
        AssignmentProposal proposal = proposedProposal();

        HexTravelTime tt1 = HexTravelTime.builder().h3GridId(gridId)
                .fromHexId(newH1).toHexId(newH2).travelTimeSeconds(300).build();
        HexTravelTime tt2 = HexTravelTime.builder().h3GridId(gridId)
                .fromHexId(newH2).toHexId(newH1).travelTimeSeconds(300).build();

        DaHexAssignment oldAssignment = proposedAssignment(proposalId, daId, oldHex);

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(gridRepository.findByCityId(cityId)).thenReturn(Optional.of(grid()));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(
                eq(gridId), anyInt())).thenReturn(List.of(tt1, tt2));
        when(assignmentRepository.findByProposalId(proposalId)).thenReturn(List.of(oldAssignment));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(regionRepository.findByProposalIdAndDaId(proposalId, daId)).thenReturn(Optional.empty());

        service.editRegionInProposal(proposalId, daId, List.of(newH1, newH2), reviewerId);

        assertThat(oldAssignment.getStatus()).isEqualTo(AssignmentStatus.SUPERSEDED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository, times(2)).saveAll(captor.capture());

        List<DaHexAssignment> newAssignments = captor.getAllValues().stream()
                .filter(l -> !l.isEmpty() && l.get(0).getStatus() == AssignmentStatus.PROPOSED)
                .findFirst().orElseThrow();
        assertThat(newAssignments).hasSize(2);
        assertThat(newAssignments).allMatch(a -> a.getDaId().equals(daId));
        assertThat(newAssignments).allMatch(a -> a.getStatus() == AssignmentStatus.PROPOSED);
    }

    @Test
    void editRegionInProposal_nonContiguousTiles_throwsIllegalState() {
        UUID daId = UUID.randomUUID();
        UUID h1 = UUID.randomUUID(), h2 = UUID.randomUUID();
        AssignmentProposal proposal = proposedProposal();

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));
        when(gridRepository.findByCityId(cityId)).thenReturn(Optional.of(grid()));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(
                eq(gridId), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.editRegionInProposal(proposalId, daId, List.of(h1, h2), reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not contiguous");
    }

    @Test
    void editRegionInProposal_nonProposedStatus_throwsIllegalState() {
        AssignmentProposal proposal = proposedProposal();
        proposal.setStatus(ProposalStatus.APPROVED);

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> service.editRegionInProposal(proposalId, UUID.randomUUID(),
                List.of(UUID.randomUUID()), reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROPOSED");
    }

    // ---- requestIntradayReassignment (Scenario B) -------------------------

    @Test
    void requestIntradayReassignment_validMove_createsOverrideProposal() {
        UUID fromDaId = UUID.randomUUID();
        UUID toDaId   = UUID.randomUUID();
        UUID sharedHex = UUID.randomUUID();
        UUID fromOtherHex = UUID.randomUUID();
        UUID toHex = UUID.randomUUID();

        DaHexAssignment fromActive1 = activeAssignment(proposalId, fromDaId, fromOtherHex);
        DaHexAssignment fromActive2 = activeAssignment(proposalId, fromDaId, sharedHex);
        DaHexAssignment toActive    = activeAssignment(proposalId, toDaId, toHex);

        HexTravelTime tt1 = HexTravelTime.builder().h3GridId(gridId)
                .fromHexId(toHex).toHexId(sharedHex).travelTimeSeconds(300).build();
        HexTravelTime tt2 = HexTravelTime.builder().h3GridId(gridId)
                .fromHexId(sharedHex).toHexId(toHex).travelTimeSeconds(300).build();

        when(gridRepository.findByCityId(cityId)).thenReturn(Optional.of(grid()));
        when(travelTimeRepository.findByH3GridIdAndTravelTimeSecondsLessThanEqual(
                eq(gridId), anyInt())).thenReturn(List.of(tt1, tt2));
        when(assignmentRepository.findByDaIdAndValidDate(eq(fromDaId), any(LocalDate.class)))
                .thenReturn(List.of(fromActive1, fromActive2));
        when(assignmentRepository.findByDaIdAndValidDate(eq(toDaId), any(LocalDate.class)))
                .thenReturn(List.of(toActive));
        when(proposalRepository.save(any())).thenAnswer(inv -> {
            AssignmentProposal p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (p.getProposedAt() == null) p.setProposedAt(Instant.now());
            return p;
        });
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        IntradayReassignmentResponse resp = service.requestIntradayReassignment(
                cityId, fromDaId, toDaId, List.of(sharedHex), reviewerId);

        assertThat(resp).isNotNull();
        assertThat(resp.fromDaId()).isEqualTo(fromDaId);
        assertThat(resp.toDaId()).isEqualTo(toDaId);
        assertThat(resp.tilesMoved()).containsExactly(sharedHex);
        assertThat(resp.status()).isEqualTo(ProposalStatus.PROPOSED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DaHexAssignment>> captor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(captor.capture());
        // fromNew = [fromOtherHex], toNew = [toHex, sharedHex] → total 3 proposed assignments
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    void requestIntradayReassignment_tileNotBelongingToFromDa_throwsIllegalState() {
        UUID fromDaId = UUID.randomUUID();
        UUID toDaId   = UUID.randomUUID();
        UUID notFromDaHex = UUID.randomUUID();

        when(assignmentRepository.findByDaIdAndValidDate(eq(fromDaId), any(LocalDate.class)))
                .thenReturn(List.of());
        when(assignmentRepository.findByDaIdAndValidDate(eq(toDaId), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.requestIntradayReassignment(
                cityId, fromDaId, toDaId, List.of(notFromDaHex), reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ACTIVE under DA");
    }

    // ---- requestTileShare -------------------------------------------------

    @Test
    void requestTileShare_activeTile_createsTileShareProposal() {
        UUID daId   = UUID.randomUUID();
        UUID hexId  = UUID.randomUUID();
        DaHexAssignment existingActive = activeAssignment(UUID.randomUUID(), UUID.randomUUID(), hexId);

        when(assignmentRepository.findByHexIdAndValidDateAndStatus(
                eq(hexId), any(LocalDate.class), eq(AssignmentStatus.ACTIVE)))
                .thenReturn(List.of(existingActive));
        when(proposalRepository.save(any())).thenAnswer(inv -> {
            AssignmentProposal p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (p.getProposedAt() == null) p.setProposedAt(Instant.now());
            return p;
        });
        when(assignmentRepository.save(any())).thenAnswer(inv -> {
            DaHexAssignment a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            if (a.getProposedAt() == null) a.setProposedAt(Instant.now());
            return a;
        });

        TileShareResponse resp = service.requestTileShare(cityId, daId, hexId, reviewerId);

        assertThat(resp.daId()).isEqualTo(daId);
        assertThat(resp.hexId()).isEqualTo(hexId);
        assertThat(resp.status()).isEqualTo(ProposalStatus.PROPOSED);

        ArgumentCaptor<AssignmentProposal> proposalCaptor = ArgumentCaptor.forClass(AssignmentProposal.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().getProposalType()).isEqualTo(ProposalType.INTRADAY_SHARE);

        ArgumentCaptor<DaHexAssignment> assignCaptor = ArgumentCaptor.forClass(DaHexAssignment.class);
        verify(assignmentRepository).save(assignCaptor.capture());
        // nDasOnHex = existing (1) + 1 = 2
        assertThat(assignCaptor.getValue().getNDasOnHex()).isEqualTo(2);
    }

    @Test
    void requestTileShare_tileHasNoActiveAssignment_throwsIllegalState() {
        UUID daId   = UUID.randomUUID();
        UUID hexId  = UUID.randomUUID();

        when(assignmentRepository.findByHexIdAndValidDateAndStatus(
                eq(hexId), any(LocalDate.class), eq(AssignmentStatus.ACTIVE)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.requestTileShare(cityId, daId, hexId, reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ACTIVE assignment");
    }

    // ---- approveTileShare -------------------------------------------------

    @Test
    void approveTileShare_proposedShare_activatesAssignment() {
        UUID shareProposalId = UUID.randomUUID();
        AssignmentProposal shareProposal = AssignmentProposal.builder()
                .cityId(cityId).validForDate(date).status(ProposalStatus.PROPOSED)
                .proposalType(ProposalType.INTRADAY_SHARE).solverType(SolverType.MANUAL)
                .adjacencySource(AdjacencySource.OSRM).totalDas(1).coveragePct(100.0)
                .understaffedHexIds("[]").build();
        shareProposal.setId(shareProposalId);
        shareProposal.setProposedAt(Instant.now());

        DaHexAssignment shareAssignment = proposedAssignment(shareProposalId,
                UUID.randomUUID(), UUID.randomUUID());

        when(proposalRepository.findById(shareProposalId)).thenReturn(Optional.of(shareProposal));
        when(assignmentRepository.findByProposalId(shareProposalId)).thenReturn(List.of(shareAssignment));
        when(assignmentRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approveTileShare(shareProposalId, reviewerId);

        assertThat(shareProposal.getStatus()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(shareAssignment.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        assertThat(shareAssignment.getApprovedBy()).isEqualTo(reviewerId);
    }

    @Test
    void approveTileShare_wrongProposalType_throwsIllegalState() {
        AssignmentProposal nightlyProposal = proposedProposal();
        nightlyProposal.setId(proposalId);

        when(proposalRepository.findById(proposalId)).thenReturn(Optional.of(nightlyProposal));

        assertThatThrownBy(() -> service.approveTileShare(proposalId, reviewerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an INTRADAY_SHARE");
    }
}
