package com.oneday.grid.batch;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// Nightly replan: runs at 01:00 IST, persists a PROPOSED AssignmentProposal for each city.
// Station manager approves by 07:00; if not, auto-fallback applies yesterday's plan.
@Component
public class NightlyReplanJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyReplanJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GridRepository gridRepository;
    private final GridReplanService gridReplanService;
    private final AssignmentProposalRepository proposalRepository;
    private final AssignmentProposalRegionRepository proposalRegionRepository;
    private final DaHexAssignmentRepository assignmentRepository;
    private final DaRosterPort daRosterPort;

    NightlyReplanJob(GridRepository gridRepository,
                     GridReplanService gridReplanService,
                     AssignmentProposalRepository proposalRepository,
                     AssignmentProposalRegionRepository proposalRegionRepository,
                     DaHexAssignmentRepository assignmentRepository,
                     DaRosterPort daRosterPort) {
        this.gridRepository = gridRepository;
        this.gridReplanService = gridReplanService;
        this.proposalRepository = proposalRepository;
        this.proposalRegionRepository = proposalRegionRepository;
        this.assignmentRepository = assignmentRepository;
        this.daRosterPort = daRosterPort;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void run() {
        LocalDate tomorrow = LocalDate.now(IST).plusDays(1);
        log.info("NightlyReplanJob starting for date={}", tomorrow);
        for (Grid grid : gridRepository.findAll()) {
            try {
                replanForCity(grid.getCityId(), tomorrow);
            } catch (Exception e) {
                log.error("NightlyReplanJob failed for cityId={}", grid.getCityId(), e);
            }
        }
        log.info("NightlyReplanJob complete for date={}", tomorrow);
    }

    // Escalation check at 06:00 IST: if no approved proposal for today, emit a warning.
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
    public void checkEscalation() {
        LocalDate today = LocalDate.now(IST);
        for (Grid grid : gridRepository.findAll()) {
            UUID cityId = grid.getCityId();
            Optional<AssignmentProposal> approved = proposalRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, today, ProposalStatus.APPROVED);
            if (approved.isEmpty()) {
                log.warn("ESCALATION_ALERT cityId={} date={}: no approved proposal by 06:00; station manager action required", cityId, today);
            }
        }
    }

    // Auto-fallback at 07:00 IST: if still no approved proposal, copy yesterday's active assignments.
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    @Transactional
    public void applyFallbackIfNeeded() {
        LocalDate today = LocalDate.now(IST);
        LocalDate yesterday = today.minusDays(1);
        for (Grid grid : gridRepository.findAll()) {
            UUID cityId = grid.getCityId();
            Optional<AssignmentProposal> approved = proposalRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, today, ProposalStatus.APPROVED);
            if (approved.isPresent()) continue;

            Optional<AssignmentProposal> yesterdayProposal = proposalRepository
                    .findByCityIdAndValidForDateAndStatus(cityId, yesterday, ProposalStatus.APPROVED);
            if (yesterdayProposal.isEmpty()) {
                log.warn("AUTO_FALLBACK_FAILED cityId={}: no approved proposal for yesterday {} either; city has no coverage", cityId, yesterday);
                continue;
            }

            applyFallback(cityId, today, yesterdayProposal.get());
        }
    }

    private void replanForCity(UUID cityId, LocalDate validForDate) {
        List<UUID> daIds = daRosterPort.getAvailableDaIds(cityId, validForDate);
        log.info("NightlyReplanJob replanForCity cityId={} date={} daCount={}", cityId, validForDate, daIds.size());
        gridReplanService.replan(cityId, validForDate, daIds);
    }

    @Transactional
    void applyFallback(UUID cityId, LocalDate today, AssignmentProposal yesterdayProposal) {
        List<DaHexAssignment> yesterdayActive = assignmentRepository
                .findByProposalId(yesterdayProposal.getId())
                .stream().filter(a -> a.getStatus() == AssignmentStatus.ACTIVE).toList();

        long daCount = yesterdayActive.stream().map(DaHexAssignment::getDaId).distinct().count();

        AssignmentProposal fallback = AssignmentProposal.builder()
                .cityId(cityId)
                .validForDate(today)
                .status(ProposalStatus.APPROVED)
                .proposalType(ProposalType.NIGHTLY)
                .solverType(SolverType.MANUAL)
                .adjacencySource(yesterdayProposal.getAdjacencySource())
                .totalDas((int) daCount)
                .notes("Auto-fallback: no approved proposal by 07:00; copied from " + today.minusDays(1))
                .build();
        fallback = proposalRepository.save(fallback);

        UUID fallbackId = fallback.getId();
        List<DaHexAssignment> todayAssignments = yesterdayActive.stream()
                .map(a -> DaHexAssignment.builder()
                        .proposalId(fallbackId)
                        .daId(a.getDaId())
                        .hexId(a.getHexId())
                        .validDate(today)
                        .nDasOnHex(a.getNDasOnHex())
                        .status(AssignmentStatus.ACTIVE)
                        .build())
                .collect(Collectors.toList());
        assignmentRepository.saveAll(todayAssignments);

        List<AssignmentProposalRegion> yesterdayRegions = proposalRegionRepository
                .findByProposalId(yesterdayProposal.getId());
        List<AssignmentProposalRegion> todayRegions = yesterdayRegions.stream()
                .map(r -> AssignmentProposalRegion.builder()
                        .proposalId(fallbackId)
                        .daId(r.getDaId())
                        .nDasRequired(r.getNDasRequired())
                        .estimatedDemandMin(r.getEstimatedDemandMin())
                        .estimatedUtilPct(r.getEstimatedUtilPct())
                        .hasBootstrappedTiles(r.isHasBootstrappedTiles())
                        .build())
                .collect(Collectors.toList());
        proposalRegionRepository.saveAll(todayRegions);

        log.info("AUTO_FALLBACK_APPLIED cityId={} date={} proposalId={} assignments={}",
                cityId, today, fallbackId, todayAssignments.size());
    }
}
