package com.oneday.routing.service.model;

import java.util.UUID;

/**
 * A candidate / selected meeting point — an H3 hex corner (M6-D-001). {@code vertexId} is the
 * grid's stable per-corner identity (the same physical corner shared by adjacent territories is
 * the same id), and is what {@code route_plan_stop} / {@code da_cron_schedule} reference.
 */
public record MeetingVertex(UUID vertexId, double lat, double lon) {}
