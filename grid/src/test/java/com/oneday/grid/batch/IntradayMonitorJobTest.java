package com.oneday.grid.batch;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.domain.AssignmentStatus;
import com.oneday.grid.domain.DaHexAssignment;
import com.oneday.grid.domain.Grid;
import com.oneday.grid.domain.Hex;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import com.oneday.grid.events.TileOverloadAlertProducer;
import com.oneday.grid.repository.DaHexAssignmentRepository;
import com.oneday.grid.repository.GridRepository;
import com.oneday.grid.repository.HexRepository;
import com.oneday.grid.service.IntradayLoadScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntradayMonitorJobTest {

    @Mock GridRepository gridRepository;
    @Mock HexRepository hexRepository;
    @Mock DaHexAssignmentRepository assignmentRepository;
    @Mock IntradayLoadScoreService loadScoreService;
    @Mock TileOverloadAlertProducer alertProducer;
    @Mock GridProperties properties;
    @Mock Grid grid;

    IntradayMonitorJob job;

    final UUID cityId = UUID.randomUUID();
    final UUID gridId = UUID.randomUUID();
    final UUID hexId = UUID.randomUUID();
    final LocalDate today = LocalDate.of(2026, 5, 20);

    @BeforeEach
    void setUp() {
        // Defaults: warning=1.5, critical=2.0, warningSustained=15, criticalSustained=10, suppress=30
        GridProperties.Intraday cfg = new GridProperties.Intraday();
        when(properties.getIntraday()).thenReturn(cfg);

        lenient().when(grid.getCityId()).thenReturn(cityId);
        lenient().when(grid.getId()).thenReturn(gridId);

        Hex hex = Hex.builder().h3GridId(gridId).h3Index(0L).active(true).build();
        hex.setId(hexId);
        lenient().when(hexRepository.findByH3GridIdAndActiveTrue(gridId)).thenReturn(List.of(hex));

        job = new IntradayMonitorJob(gridRepository, hexRepository, assignmentRepository,
                loadScoreService, alertProducer, properties);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Integer> sustainedMinutes() {
        return (Map<UUID, Integer>) ReflectionTestUtils.getField(job, "sustainedMinutes");
    }

    private void invokeMonitorCity() {
        ReflectionTestUtils.invokeMethod(job, "monitorCity", grid, today);
    }

    private void preSeed(UUID id, int minutes) {
        sustainedMinutes().put(id, minutes);
    }

    // ---- A4 tests ----------------------------------------------------------

    @Test
    void monitor_tileExceedsWarningThreshold_incrementsHysteresisCounter() {
        // adjustedLoadScore=1.6 ≥ warning(1.5) but < critical(2.0)
        when(loadScoreService.getLoadScore(hexId, today))
                .thenReturn(new TileLoadScoreResponse(hexId, today, 3, 1.6, "WARNING"));

        invokeMonitorCity();

        // POLL_INTERVAL_MINUTES = 5 → counter increments from 0 to 5
        assertThat(sustainedMinutes().get(hexId)).isEqualTo(5);
    }

    @Test
    void monitor_tileExceedsCriticalSustained_firesOverloadAlert() {
        // Pre-seed: one tick already sustained (5 min), next tick reaches criticalSustained=10
        preSeed(hexId, 5);

        when(loadScoreService.getLoadScore(hexId, today))
                .thenReturn(new TileLoadScoreResponse(hexId, today, 5, 2.5, "CRITICAL"));

        DaHexAssignment assignment = DaHexAssignment.builder()
                .daId(UUID.randomUUID()).hexId(hexId).validDate(today)
                .proposalId(UUID.randomUUID()).status(AssignmentStatus.ACTIVE).build();
        when(assignmentRepository.findByHexIdAndValidDateAndStatus(hexId, today, AssignmentStatus.ACTIVE))
                .thenReturn(List.of(assignment));

        invokeMonitorCity();

        // sustained = 5 + 5 = 10 ≥ criticalSustainedMinutes(10) → alert fired
        verify(alertProducer).emit(eq(cityId), eq(hexId), any(), eq(today),
                eq("CRITICAL"), any(double.class), any(int.class), any(double.class), any(int.class));
    }

    @Test
    void monitor_tileFallsBelowThreshold_resetsHysteresisCounter() {
        preSeed(hexId, 10);

        // adjustedLoadScore=1.0 < warning(1.5) → below threshold
        when(loadScoreService.getLoadScore(hexId, today))
                .thenReturn(new TileLoadScoreResponse(hexId, today, 0, 1.0, "OK"));

        invokeMonitorCity();

        assertThat(sustainedMinutes().get(hexId)).isNull();
    }

    @Test
    void monitor_tileWarningNotSustained_noAlertFired() {
        // warningSustainedMinutes=15; first tick gives sustained=5 < 15 → no alert
        when(loadScoreService.getLoadScore(hexId, today))
                .thenReturn(new TileLoadScoreResponse(hexId, today, 2, 1.6, "WARNING"));

        invokeMonitorCity();

        assertThat(sustainedMinutes().get(hexId)).isEqualTo(5);
        verify(alertProducer, never()).emit(any(), any(), any(), any(), any(),
                any(double.class), any(int.class), any(double.class), any(int.class));
    }
}
