package com.oneday.routing.service;

import com.oneday.routing.config.RoutingProperties;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.osrm.RoutingOsrmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the matrix is built from OSRM's /table response: rounding, diagonal, penalty. Uses a
 * hand-written {@link RoutingOsrmClient} stub (not Mockito) — the OR-Tools native lib loaded by the
 * solver tests breaks Byte Buddy's agent in the shared JVM, and a stub sidesteps it entirely.
 */
class TravelMatrixServiceTest {

    /** A {@link RoutingOsrmClient} that returns a canned duration table regardless of input. */
    private static RoutingOsrmClient stubOsrm(double[][] table) {
        return new RoutingOsrmClient("http://stub", null) {
            @Override
            public double[][] getTable(List<double[]> latLons) {
                return table;
            }
        };
    }

    private static RoutingNode vertex(int index) {
        return new RoutingNode(index, StopNodeKind.MEETING_VERTEX, UUID.randomUUID(),
                12.9 + index, 77.5 + index, 2, 1, 60);
    }

    /** Default props → congestionFactor 1.0 (raw OSRM); a test can set its own. */
    private static RoutingProperties props(double congestion) {
        RoutingProperties p = new RoutingProperties();
        p.setCongestionFactor(congestion);
        return p;
    }

    @Test
    void buildsMatrixFromOsrmTable() {
        TravelMatrixService service = new TravelMatrixService(stubOsrm(new double[][]{
                {0.0, 120.4, 300.6},
                {119.8, 0.0, 180.2},
                {301.1, 181.9, 0.0}
        }), props(1.0));

        TravelMatrix matrix = service.buildMatrix(List.of(
                RoutingNode.hub(UUID.randomUUID(), 12.9, 77.5), vertex(1), vertex(2)));

        assertThat(matrix.size()).isEqualTo(3);
        assertThat(matrix.travel(0, 0)).isZero();          // diagonal forced to 0
        assertThat(matrix.travel(0, 1)).isEqualTo(120);    // rounded
        assertThat(matrix.travel(0, 2)).isEqualTo(301);
        assertThat(matrix.travel(1, 0)).isEqualTo(120);
    }

    @Test
    void unreachablePairGetsLargePenalty() {
        TravelMatrixService service = new TravelMatrixService(stubOsrm(new double[][]{
                {0.0, -1.0},
                {Double.NaN, 0.0}
        }), props(1.0));

        TravelMatrix matrix = service.buildMatrix(List.of(
                RoutingNode.hub(UUID.randomUUID(), 12.9, 77.5), vertex(1)));

        assertThat(matrix.travel(0, 1)).isGreaterThan(50_000L);
        assertThat(matrix.travel(1, 0)).isGreaterThan(50_000L);
    }

    @Test
    void congestionFactorScalesTravelTimes() {
        TravelMatrixService service = new TravelMatrixService(stubOsrm(new double[][]{
                {0.0, 100.0},
                {100.0, 0.0}
        }), props(1.6));

        TravelMatrix matrix = service.buildMatrix(List.of(
                RoutingNode.hub(UUID.randomUUID(), 12.9, 77.5), vertex(1)));

        assertThat(matrix.travel(0, 1)).isEqualTo(160);   // 100s × 1.6
        assertThat(matrix.travel(0, 0)).isZero();         // diagonal still 0
    }
}
