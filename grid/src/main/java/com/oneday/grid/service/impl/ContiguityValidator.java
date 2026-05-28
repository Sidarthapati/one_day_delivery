package com.oneday.grid.service.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Shared by BfsAssignmentServiceImpl and ProposalService for contiguity checks.
class ContiguityValidator {

    private ContiguityValidator() {}

    static boolean isConnected(List<UUID> tileIds, Map<UUID, List<UUID>> adjacencyGraph) {
        if (tileIds.size() <= 1) return true;
        Set<UUID> tileSet = new HashSet<>(tileIds);
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(tileIds.get(0));
        visited.add(tileIds.get(0));
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (UUID neighbor : adjacencyGraph.getOrDefault(current, List.of())) {
                if (tileSet.contains(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited.size() == tileSet.size();
    }

    // Returns connected components of tileIds, ordered largest-first by tile count.
    static List<List<UUID>> findConnectedComponents(List<UUID> tileIds, Map<UUID, List<UUID>> adjacencyGraph) {
        Set<UUID> tileSet = new HashSet<>(tileIds);
        Set<UUID> unvisited = new HashSet<>(tileIds);
        List<List<UUID>> components = new ArrayList<>();
        while (!unvisited.isEmpty()) {
            UUID start = unvisited.iterator().next();
            List<UUID> component = new ArrayList<>();
            Deque<UUID> queue = new ArrayDeque<>();
            queue.add(start);
            unvisited.remove(start);
            while (!queue.isEmpty()) {
                UUID current = queue.poll();
                component.add(current);
                for (UUID neighbor : adjacencyGraph.getOrDefault(current, List.of())) {
                    if (tileSet.contains(neighbor) && unvisited.remove(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }
        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return components;
    }
}
