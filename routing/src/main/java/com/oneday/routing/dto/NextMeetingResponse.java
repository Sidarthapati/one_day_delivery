package com.oneday.routing.dto;

import java.time.LocalTime;
import java.util.UUID;

/** The single next meeting at/after {@code at} ({@code GET /routing/cron/da/{daId}/next}) — M5 convenience. */
public record NextMeetingResponse(
        UUID daId,
        UUID hexVertexId,
        UUID vanId,
        LocalTime nextMeeting) {}
