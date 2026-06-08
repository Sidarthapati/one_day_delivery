package com.oneday.routing.service.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Output of meeting-point selection (§7.2, M6-D-001): the chosen meeting vertices and who meets
 * where. {@code vertexToDaIds} maps each selected vertex → the DAs that meet there;
 * {@code daToVertex} is the inverse single assignment (each DA's one meeting vertex for v1).
 */
public record MeetingPlan(
        List<MeetingVertex> vertices,
        Map<UUID, List<UUID>> vertexToDaIds,
        Map<UUID, UUID> daToVertex
) {}
