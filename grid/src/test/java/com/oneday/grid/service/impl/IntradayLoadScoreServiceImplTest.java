package com.oneday.grid.service.impl;

import com.oneday.grid.config.GridProperties;
import com.oneday.grid.dto.response.TileLoadScoreResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IntradayLoadScoreServiceImplTest {

    private final UUID tileId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 5, 18);

    // Default props: warning=1.5, critical=2.0
    private GridProperties defaultProps = new GridProperties();
    private IntradayLoadScoreServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IntradayLoadScoreServiceImpl(defaultProps);
    }

    // ---- getLoadScore with no prior update --------------------------------

    @Test
    void getLoadScore_unknownTile_returnsZeroAndOk() {
        TileLoadScoreResponse resp = service.getLoadScore(UUID.randomUUID(), date);

        assertThat(resp.unservedOrders()).isEqualTo(0);
        assertThat(resp.adjustedLoadScore()).isEqualTo(0.0);
        assertThat(resp.severity()).isEqualTo("OK");
    }

    // ---- severity bands ---------------------------------------------------

    @Test
    void severity_unservedBelowWarning_returnsOk() {
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 1));

        TileLoadScoreResponse resp = service.getLoadScore(tileId, date);

        // score=1.0 < warning=1.5 → OK
        assertThat(resp.severity()).isEqualTo("OK");
        assertThat(resp.unservedOrders()).isEqualTo(1);
    }

    @Test
    void severity_unservedAtCritical_returnsCritical() {
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 2));

        TileLoadScoreResponse resp = service.getLoadScore(tileId, date);

        // score=2.0 >= critical=2.0 → CRITICAL (checked first, before WARNING)
        assertThat(resp.severity()).isEqualTo("CRITICAL");
        assertThat(resp.unservedOrders()).isEqualTo(2);
    }

    @Test
    void severity_unservedAboveCritical_returnsCritical() {
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 5));

        assertThat(service.getLoadScore(tileId, date).severity()).isEqualTo("CRITICAL");
    }

    @Test
    void severity_warningBand_returnsWarning() {
        // Use custom props where warning=0.5, critical=5.0 so score=1 lands in WARNING
        GridProperties warnProps = new GridProperties();
        warnProps.getIntraday().setOverloadWarningThreshold(0.5);
        warnProps.getIntraday().setOverloadCriticalThreshold(5.0);
        IntradayLoadScoreServiceImpl warnService = new IntradayLoadScoreServiceImpl(warnProps);

        warnService.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 1));

        TileLoadScoreResponse resp = warnService.getLoadScore(tileId, date);

        // score=1.0 >= 0.5 (warning) but < 5.0 (critical) → WARNING
        assertThat(resp.severity()).isEqualTo("WARNING");
    }

    // ---- updateQueueDepth ------------------------------------------------

    @Test
    void updateQueueDepth_storesMultipleTiles() {
        UUID tileA = UUID.randomUUID(), tileB = UUID.randomUUID();
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileA, 3, tileB, 1));

        assertThat(service.getLoadScore(tileA, date).unservedOrders()).isEqualTo(3);
        assertThat(service.getLoadScore(tileB, date).unservedOrders()).isEqualTo(1);
    }

    @Test
    void updateQueueDepth_calledTwice_latestValueWins() {
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 3));
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 5));

        // putAll overwrites the previous value for the same key
        assertThat(service.getLoadScore(tileId, date).unservedOrders()).isEqualTo(5);
    }

    // ---- resetForShift ---------------------------------------------------

    @Test
    void resetForShift_clearsAllEntries() {
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(tileId, 3));
        service.resetForShift();

        TileLoadScoreResponse resp = service.getLoadScore(tileId, date);

        assertThat(resp.unservedOrders()).isEqualTo(0);
        assertThat(resp.severity()).isEqualTo("OK");
    }

    @Test
    void resetForShift_multipleUpdates_allCleared() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        service.updateQueueDepth(UUID.randomUUID(), date, Map.of(t1, 2, t2, 4));
        service.resetForShift();

        assertThat(service.getLoadScore(t1, date).unservedOrders()).isEqualTo(0);
        assertThat(service.getLoadScore(t2, date).unservedOrders()).isEqualTo(0);
    }
}
