package com.oneday.routing.service.impl;

import com.oneday.routing.service.MeetingPointSelectionService;
import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.MeetingPlan;
import com.oneday.routing.service.model.MeetingVertex;
import com.oneday.routing.service.model.TerritoryHex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Set-cover (§7.2): covers every DA and prefers the high-degree shared vertex. */
class GreedyMeetingPointSelectionServiceTest {

    private final MeetingPointSelectionService service = new GreedyMeetingPointSelectionService();

    @Test
    void prefersSharedThreeTerritoryVertexAndCoversAll() {
        MeetingVertex shared = vertex();        // touched by all 3 DAs
        MeetingVertex privA = vertex();
        MeetingVertex privB = vertex();
        MeetingVertex privC = vertex();

        UUID daA = UUID.randomUUID();
        UUID daB = UUID.randomUUID();
        UUID daC = UUID.randomUUID();

        List<DaTerritory> territories = List.of(
                new DaTerritory(daA, List.of(hex(shared, privA))),
                new DaTerritory(daB, List.of(hex(shared, privB))),
                new DaTerritory(daC, List.of(hex(shared, privC)))
        );

        MeetingPlan plan = service.select(territories);

        // One shared vertex covers all three → minimal cover is just it.
        assertThat(plan.vertices()).extracting(MeetingVertex::vertexId).containsExactly(shared.vertexId());
        assertThat(plan.daToVertex()).containsOnlyKeys(daA, daB, daC);
        assertThat(plan.daToVertex().values()).containsOnly(shared.vertexId());
        assertThat(plan.vertexToDaIds().get(shared.vertexId())).containsExactlyInAnyOrder(daA, daB, daC);
    }

    @Test
    void coversDisjointTerritoriesWithSeparateVertices() {
        MeetingVertex vA = vertex();
        MeetingVertex vB = vertex();
        UUID daA = UUID.randomUUID();
        UUID daB = UUID.randomUUID();

        List<DaTerritory> territories = List.of(
                new DaTerritory(daA, List.of(hex(vA))),
                new DaTerritory(daB, List.of(hex(vB)))
        );

        MeetingPlan plan = service.select(territories);

        assertThat(plan.vertices()).hasSize(2);
        assertThat(plan.daToVertex()).containsOnlyKeys(daA, daB);
        assertThat(plan.daToVertex().get(daA)).isEqualTo(vA.vertexId());
        assertThat(plan.daToVertex().get(daB)).isEqualTo(vB.vertexId());
    }

    private static MeetingVertex vertex() {
        return new MeetingVertex(UUID.randomUUID(), 12.9 + Math.random() / 100, 77.5 + Math.random() / 100);
    }

    private static TerritoryHex hex(MeetingVertex... vertices) {
        return new TerritoryHex(UUID.randomUUID(), 0L, 10.0, 5.0, List.of(vertices));
    }
}
