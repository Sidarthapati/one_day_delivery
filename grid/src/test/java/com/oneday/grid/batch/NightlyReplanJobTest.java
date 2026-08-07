package com.oneday.grid.batch;

import com.oneday.grid.domain.AdjacencySource;
import com.oneday.grid.domain.AssignmentProposal;
import com.oneday.grid.domain.AssignmentProposalRegion;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.ProposalStatus;
import com.oneday.grid.domain.ProposalType;
import com.oneday.grid.domain.SolverType;
import com.oneday.grid.repository.AssignmentProposalRegionRepository;
import com.oneday.grid.repository.AssignmentProposalRepository;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.service.GridReplanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NightlyReplanJobTest {

    @Mock GridRepository gridRepository;
    @Mock GridReplanService gridReplanService;
    @Mock AssignmentProposalRepository proposalRepository;
    @Mock AssignmentProposalRegionRepository proposalRegionRepository;
    @Mock DaHexAssignmentRepository assignmentRepository;
    @Mock DaRosterPort daRosterPort;

    NightlyReplanJob job;

    @BeforeEach
    void setUp() {
        job = new NightlyReplanJob(gridRepository, gridReplanService, proposalRepository,
                proposalRegionRepository, assignmentRepository, daRosterPort);
    }

    private Grid gridFor(UUID cityId) {
        Grid g = mock(Grid.class);
        when(g.getCityId()).thenReturn(cityId);
        return g;
    }

    // ---- A3 tests ----------------------------------------------------------

    // The nightly plan runs once per shift, so per-city counts double (2 shifts).

    @Test
    void run_iteratesAllConfiguredCities_callsReplanPerShift() {
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), c3 = UUID.randomUUID();
        Grid g1 = gridFor(c1), g2 = gridFor(c2), g3 = gridFor(c3);
        when(gridRepository.findAll()).thenReturn(List.of(g1, g2, g3));
        when(daRosterPort.getAvailableDaIds(any(), any(), any())).thenReturn(List.of(UUID.randomUUID()));

        job.run();

        // 3 cities × 2 shifts
        verify(gridReplanService, times(6)).replan(any(), any(), any(), anyList());
    }

    @Test
    void run_noDasFromRoster_stillCallsReplanWithEmptyList_perShift() {
        UUID cityId = UUID.randomUUID();
        Grid g = gridFor(cityId);
        when(gridRepository.findAll()).thenReturn(List.of(g));
        when(daRosterPort.getAvailableDaIds(eq(cityId), any(), any())).thenReturn(List.of());

        job.run();

        verify(gridReplanService, times(2)).replan(eq(cityId), any(), any(), eq(List.of()));
    }

    @Test
    void checkEscalation_noApprovedProposal_queriesProposalRepositoryPerShift() {
        UUID cityId = UUID.randomUUID();
        Grid g = gridFor(cityId);
        when(gridRepository.findAll()).thenReturn(List.of(g));
        when(proposalRepository.findByCityIdAndValidForDateAndShiftAndStatus(eq(cityId), any(), any(), eq(ProposalStatus.APPROVED)))
                .thenReturn(Optional.empty());

        job.checkEscalation();

        verify(proposalRepository, times(2))
                .findByCityIdAndValidForDateAndShiftAndStatus(eq(cityId), any(), any(), eq(ProposalStatus.APPROVED));
    }

    @Test
    void applyFallbackIfNeeded_noApprovedProposal_copiesYesterdayAssignments() {
        UUID cityId = UUID.randomUUID();
        UUID yesterdayProposalId = UUID.randomUUID();
        // Match the job: it reads today/yesterday in IST, so the stubs must too
        // (otherwise a run near the UTC/IST midnight boundary shifts the dates apart).
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        Grid g = gridFor(cityId);
        when(gridRepository.findAll()).thenReturn(List.of(g));

        AssignmentProposal yesterdayProposal = AssignmentProposal.builder()
                .cityId(cityId).validForDate(today.minusDays(1))
                .status(ProposalStatus.APPROVED).proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.CP_SAT).adjacencySource(AdjacencySource.OSRM)
                .totalDas(1).build();
        yesterdayProposal.setId(yesterdayProposalId);

        // today's APPROVED absent (both shifts) → yesterday's APPROVED present (both shifts)
        lenient().when(proposalRepository.findByCityIdAndValidForDateAndShiftAndStatus(
                        eq(cityId), eq(today), any(), eq(ProposalStatus.APPROVED)))
                .thenReturn(Optional.empty());
        lenient().when(proposalRepository.findByCityIdAndValidForDateAndShiftAndStatus(
                        eq(cityId), eq(today.minusDays(1)), any(), eq(ProposalStatus.APPROVED)))
                .thenReturn(Optional.of(yesterdayProposal));

        DaHexAssignment activeAssignment = DaHexAssignment.builder()
                .proposalId(yesterdayProposalId).daId(UUID.randomUUID()).hexId(UUID.randomUUID())
                .validDate(today.minusDays(1)).status(AssignmentStatus.APPROVED).build();
        when(assignmentRepository.findByProposalId(yesterdayProposalId))
                .thenReturn(List.of(activeAssignment));
        when(proposalRegionRepository.findByProposalId(yesterdayProposalId))
                .thenReturn(List.of(AssignmentProposalRegion.builder()
                        .proposalId(yesterdayProposalId).daId(activeAssignment.getDaId())
                        .estimatedDemandMin(100.0).estimatedUtilPct(0.7).build()));

        AssignmentProposal savedFallback = AssignmentProposal.builder()
                .cityId(cityId).validForDate(today)
                .status(ProposalStatus.APPROVED).proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.MANUAL).adjacencySource(AdjacencySource.OSRM)
                .totalDas(1).build();
        savedFallback.setId(UUID.randomUUID());
        when(proposalRepository.save(any(AssignmentProposal.class))).thenReturn(savedFallback);

        job.applyFallbackIfNeeded();

        // Once per shift.
        verify(assignmentRepository, times(2)).saveAll(anyList());
        verify(proposalRegionRepository, times(2)).saveAll(anyList());
    }

    @Test
    void applyFallbackIfNeeded_approvedProposalExists_doesNothing() {
        UUID cityId = UUID.randomUUID();
        Grid g = gridFor(cityId);
        when(gridRepository.findAll()).thenReturn(List.of(g));

        AssignmentProposal approved = AssignmentProposal.builder()
                .cityId(cityId).validForDate(LocalDate.now())
                .status(ProposalStatus.APPROVED).proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.CP_SAT).adjacencySource(AdjacencySource.OSRM)
                .totalDas(2).build();
        approved.setId(UUID.randomUUID());
        when(proposalRepository.findByCityIdAndValidForDateAndShiftAndStatus(any(), any(), any(), eq(ProposalStatus.APPROVED)))
                .thenReturn(Optional.of(approved));

        job.applyFallbackIfNeeded();

        verify(assignmentRepository, never()).saveAll(anyList());
        verify(proposalRegionRepository, never()).saveAll(anyList());
    }
}
