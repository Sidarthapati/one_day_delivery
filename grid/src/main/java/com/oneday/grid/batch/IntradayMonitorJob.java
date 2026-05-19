package com.oneday.grid.batch;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaTileAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Tile;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.events.TileOverloadAlertProducer;
import com.oneday.grid.repository.DaTileAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.TileRepository;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// Runs every 5 minutes during shift hours (07:00–20:00 IST).
// Reads unserved-order counts from the in-memory load score service (populated by TileQueueDepthConsumer)
// and emits overload alerts when thresholds are sustained.
@Component
public class IntradayMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(IntradayMonitorJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int POLL_INTERVAL_MINUTES = 5;

    private final GridRepository gridRepository;
    private final TileRepository tileRepository;
    private final DaTileAssignmentRepository assignmentRepository;
    private final IntradayLoadScoreService loadScoreService;
    private final TileOverloadAlertProducer alertProducer;
    private final GridProperties properties;

    // In-memory hysteresis state — reset at shift start.
    private final Map<UUID, Integer> sustainedMinutes = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastAlertAt = new ConcurrentHashMap<>();

    IntradayMonitorJob(GridRepository gridRepository,
                       TileRepository tileRepository,
                       DaTileAssignmentRepository assignmentRepository,
                       IntradayLoadScoreService loadScoreService,
                       TileOverloadAlertProducer alertProducer,
                       GridProperties properties) {
        this.gridRepository = gridRepository;
        this.tileRepository = tileRepository;
        this.assignmentRepository = assignmentRepository;
        this.loadScoreService = loadScoreService;
        this.alertProducer = alertProducer;
        this.properties = properties;
    }

    @Scheduled(fixedRate = POLL_INTERVAL_MINUTES, timeUnit = TimeUnit.MINUTES)
    public void run() {
        LocalDate today = LocalDate.now(IST);
        LocalTime nowTime = LocalTime.now(IST);
        GridProperties.Shift shift = properties.getShift();

        if (nowTime.isBefore(LocalTime.of(shift.getStartHour(), 0))) {
            // Before shift: reset state and leave early (the 07:00 tick still runs once).
            if (nowTime.isAfter(LocalTime.of(shift.getStartHour() - 1, 55))) {
                resetShiftState();
                loadScoreService.resetForShift();
                log.info("IntradayMonitorJob: shift start reset at {}", nowTime);
            }
            return;
        }
        if (nowTime.isAfter(LocalTime.of(shift.getEndHour(), 0))) {
            return;
        }

        for (Grid grid : gridRepository.findAll()) {
            monitorCity(grid, today);
        }
    }

    private void monitorCity(Grid grid, LocalDate today) {
        UUID cityId = grid.getCityId();
        List<Tile> activeTiles = tileRepository.findByGridIdAndActiveTrue(grid.getId());
        GridProperties.Intraday cfg = properties.getIntraday();

        for (Tile tile : activeTiles) {
            UUID tileId = tile.getId();
            TileLoadScoreResponse score = loadScoreService.getLoadScore(tileId, today);
            double adjustedScore = score.adjustedLoadScore();
            String severity = score.severity();

            boolean aboveWarning = adjustedScore >= cfg.getOverloadWarningThreshold();
            boolean aboveCritical = adjustedScore >= cfg.getOverloadCriticalThreshold();

            if (aboveWarning) {
                int sustained = sustainedMinutes.merge(tileId, POLL_INTERVAL_MINUTES, Integer::sum);
                int requiredMinutes = aboveCritical
                        ? cfg.getCriticalSustainedMinutes()
                        : cfg.getWarningSustainedMinutes();

                if (sustained >= requiredMinutes && canAlert(tileId, cfg.getReAlertSuppressionMinutes())) {
                    UUID daId = resolveDaId(tileId, today);
                    alertProducer.emit(cityId, tileId, daId, today, severity,
                            0.0, score.unservedOrders(), adjustedScore, sustained);
                    lastAlertAt.put(tileId, Instant.now());

                    if (aboveCritical) {
                        // Level 3 auto-suggestion (BFS rebalance) is not yet implemented.
                        log.warn("LEVEL3_SUGGESTION_PENDING tileId={}: CRITICAL overload sustained {}min — manual review required",
                                tileId, sustained);
                    }
                }
            } else {
                // Below threshold: reset hysteresis counter.
                sustainedMinutes.remove(tileId);
            }
        }
    }

    private boolean canAlert(UUID tileId, int suppressionMinutes) {
        Instant last = lastAlertAt.get(tileId);
        if (last == null) return true;
        long minutesSince = java.time.Duration.between(last, Instant.now()).toMinutes();
        return minutesSince >= suppressionMinutes;
    }

    // Returns the first active DA ID assigned to the tile today, or null if none found.
    private UUID resolveDaId(UUID tileId, LocalDate today) {
        List<DaTileAssignment> active = assignmentRepository
                .findByTileIdAndValidDateAndStatus(tileId, today, AssignmentStatus.ACTIVE);
        return active.isEmpty() ? null : active.get(0).getDaId();
    }

    private void resetShiftState() {
        sustainedMinutes.clear();
        lastAlertAt.clear();
    }
}
