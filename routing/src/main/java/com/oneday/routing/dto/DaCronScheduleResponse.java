package com.oneday.routing.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** A DA's vertex + the day's meeting times ({@code GET /routing/cron/da/{daId}}) — M5's input. */
public record DaCronScheduleResponse(
        UUID daId,
        UUID cityId,
        LocalDate validDate,
        UUID routePlanId,
        UUID hexVertexId,
        UUID vanId,
        List<LocalTime> meetingTimes) {}
