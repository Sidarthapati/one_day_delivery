package com.oneday.routing.service.impl;

import com.oneday.routing.service.MeetingPointSelectionService;
import com.oneday.routing.service.model.DaTerritory;
import com.oneday.routing.service.model.MeetingPlan;
import com.oneday.routing.service.model.MeetingVertex;
import com.oneday.routing.service.model.TerritoryHex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Weighted greedy max-coverage set-cover (§7.2, M6-D-001). Each DA's candidate meeting points are
 * its hexes' corner vertices — by construction within ~one hex edge of its territory, so the
 * {@code max_da_to_vertex_minutes} reachability bound (C5/NFR-4) holds without an OSRM check here
 * (a stricter distance filter can layer on once the matrix is available).
 *
 * <p>Greedy repeatedly picks the vertex covering the most still-uncovered DAs, tie-broken toward
 * the higher-degree (more shared) vertex; ln(n) approximation, ample at city scale. Each DA is
 * assigned to exactly one meeting vertex (v1).
 */
@Service
class GreedyMeetingPointSelectionService implements MeetingPointSelectionService {

    private static final Logger log = LoggerFactory.getLogger(GreedyMeetingPointSelectionService.class);

    @Override
    public MeetingPlan select(List<DaTerritory> territories) {
        // Catalogue every candidate vertex and which DAs can use it.
        Map<UUID, MeetingVertex> vertexById = new HashMap<>();
        Map<UUID, Set<UUID>> daIdsByVertex = new HashMap<>();   // vertexId → candidate DA ids (degree)
        Set<UUID> daToCover = new HashSet<>();

        for (DaTerritory territory : territories) {
            Set<UUID> daCandidates = new HashSet<>();
            for (TerritoryHex hex : territory.hexes()) {
                for (MeetingVertex v : hex.vertices()) {
                    vertexById.putIfAbsent(v.vertexId(), v);
                    daIdsByVertex.computeIfAbsent(v.vertexId(), k -> new HashSet<>()).add(territory.daId());
                    daCandidates.add(v.vertexId());
                }
            }
            if (daCandidates.isEmpty()) {
                log.warn("DA {} has no candidate meeting vertices (no hex corners) — not coverable, skipping", territory.daId());
            } else {
                daToCover.add(territory.daId());
            }
        }

        Map<UUID, List<UUID>> vertexToDaIds = new LinkedHashMap<>();
        Map<UUID, UUID> daToVertex = new HashMap<>();
        Set<UUID> uncovered = new HashSet<>(daToCover);

        while (!uncovered.isEmpty()) {
            UUID best = pickBestVertex(daIdsByVertex, uncovered);
            if (best == null) break; // no vertex covers any remaining DA (shouldn't happen — each has ≥1)

            List<UUID> newlyCovered = new ArrayList<>();
            for (UUID daId : daIdsByVertex.get(best)) {
                if (uncovered.remove(daId)) {
                    newlyCovered.add(daId);
                    daToVertex.put(daId, best);
                }
            }
            vertexToDaIds.put(best, newlyCovered);
        }

        List<MeetingVertex> selected = vertexToDaIds.keySet().stream().map(vertexById::get).toList();
        return new MeetingPlan(selected, vertexToDaIds, daToVertex);
    }

    /** Vertex covering the most uncovered DAs; ties broken toward higher total degree, then id. */
    private static UUID pickBestVertex(Map<UUID, Set<UUID>> daIdsByVertex, Set<UUID> uncovered) {
        UUID best = null;
        int bestNew = 0;
        int bestDegree = 0;
        for (Map.Entry<UUID, Set<UUID>> e : daIdsByVertex.entrySet()) {
            int newCount = 0;
            for (UUID daId : e.getValue()) {
                if (uncovered.contains(daId)) newCount++;
            }
            if (newCount == 0) continue;
            int degree = e.getValue().size();
            if (newCount > bestNew
                    || (newCount == bestNew && degree > bestDegree)
                    || (newCount == bestNew && degree == bestDegree && (best == null || e.getKey().compareTo(best) < 0))) {
                best = e.getKey();
                bestNew = newCount;
                bestDegree = degree;
            }
        }
        return best;
    }
}
