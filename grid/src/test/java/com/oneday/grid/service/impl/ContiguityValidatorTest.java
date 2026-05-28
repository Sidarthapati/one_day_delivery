package com.oneday.grid.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContiguityValidatorTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    // ---- isConnected -------------------------------------------------------

    @Test
    void isConnected_emptyList_returnsTrue() {
        assertThat(ContiguityValidator.isConnected(List.of(), Map.of())).isTrue();
    }

    @Test
    void isConnected_singleTile_returnsTrue() {
        assertThat(ContiguityValidator.isConnected(List.of(A), Map.of())).isTrue();
    }

    @Test
    void isConnected_twoConnectedTiles_returnsTrue() {
        Map<UUID, List<UUID>> graph = Map.of(A, List.of(B), B, List.of(A));
        assertThat(ContiguityValidator.isConnected(List.of(A, B), graph)).isTrue();
    }

    @Test
    void isConnected_twoDisconnectedTiles_returnsFalse() {
        // A and B in the tile set, but the graph has no edge between them
        assertThat(ContiguityValidator.isConnected(List.of(A, B), Map.of())).isFalse();
    }

    @Test
    void isConnected_linearChain_returnsTrue() {
        // A - B - C
        Map<UUID, List<UUID>> graph = Map.of(
                A, List.of(B),
                B, List.of(A, C),
                C, List.of(B)
        );
        assertThat(ContiguityValidator.isConnected(List.of(A, B, C), graph)).isTrue();
    }

    @Test
    void isConnected_chainWithGap_returnsFalse() {
        // A - B   C (C is isolated from A-B)
        Map<UUID, List<UUID>> graph = Map.of(A, List.of(B), B, List.of(A));
        assertThat(ContiguityValidator.isConnected(List.of(A, B, C), graph)).isFalse();
    }

    @Test
    void isConnected_tileSetSubsetOfGraph_checksOnlySubset() {
        // Graph has A-B-C-D, but tile set is only {A, C} — no direct edge
        Map<UUID, List<UUID>> graph = Map.of(
                A, List.of(B), B, List.of(A, C), C, List.of(B, D), D, List.of(C)
        );
        assertThat(ContiguityValidator.isConnected(List.of(A, C), graph)).isFalse();
    }

    // ---- findConnectedComponents -------------------------------------------

    @Test
    void findConnectedComponents_emptyList_returnsEmptyComponents() {
        assertThat(ContiguityValidator.findConnectedComponents(List.of(), Map.of())).isEmpty();
    }

    @Test
    void findConnectedComponents_singleTile_returnsOneComponent() {
        List<List<UUID>> comps = ContiguityValidator.findConnectedComponents(List.of(A), Map.of());
        assertThat(comps).hasSize(1);
        assertThat(comps.get(0)).containsExactly(A);
    }

    @Test
    void findConnectedComponents_oneConnectedComponent_returnsSingleList() {
        Map<UUID, List<UUID>> graph = Map.of(A, List.of(B), B, List.of(A));
        List<List<UUID>> comps = ContiguityValidator.findConnectedComponents(List.of(A, B), graph);
        assertThat(comps).hasSize(1);
        assertThat(comps.get(0)).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void findConnectedComponents_twoIsolatedComponents_returnsBoth() {
        // {A, B} connected; {C, D} connected; no bridge
        Map<UUID, List<UUID>> graph = Map.of(
                A, List.of(B), B, List.of(A),
                C, List.of(D), D, List.of(C)
        );
        List<List<UUID>> comps = ContiguityValidator.findConnectedComponents(List.of(A, B, C, D), graph);
        assertThat(comps).hasSize(2);
        // Largest component first
        assertThat(comps.get(0)).hasSize(2);
        assertThat(comps.get(1)).hasSize(2);
    }

    @Test
    void findConnectedComponents_orderedLargestFirst() {
        // A alone; B-C-D together
        Map<UUID, List<UUID>> graph = Map.of(
                B, List.of(C), C, List.of(B, D), D, List.of(C)
        );
        List<List<UUID>> comps = ContiguityValidator.findConnectedComponents(List.of(A, B, C, D), graph);
        assertThat(comps).hasSize(2);
        assertThat(comps.get(0)).hasSize(3); // B-C-D is largest
        assertThat(comps.get(1)).hasSize(1); // A alone
    }
}
