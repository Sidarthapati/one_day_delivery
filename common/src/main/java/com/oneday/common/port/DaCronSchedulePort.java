package com.oneday.common.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read M6's per-DA cron schedule (the DA↔van rendezvous: vertex, the van it meets, meeting times)
 * without importing the routing module. M5's demo shift-load uses it to seat each DA on its <b>real</b>
 * meeting vertex and link it to its van, rather than synthesizing a placeholder. Implemented in routing
 * (reads {@code da_cron_schedule} for the date's APPROVED plan), consumed in dispatch — both depend only
 * on {@code common}, like the other cross-module ports. Reads the source of truth directly, so it does
 * not depend on the async {@code DaCronScheduledEvent} having already been delivered.
 */
public interface DaCronSchedulePort {

    /** One DA's scheduled van rendezvous for the day. {@code meetingTimes} are ISO LocalTime strings. */
    record DaCron(UUID daId, UUID vanId, UUID cronVertexId,
                  double meetingLat, double meetingLon, List<String> meetingTimes) {}

    /** Cron schedules for the city's APPROVED route plan on the date (empty if no plan yet). */
    List<DaCron> cronsForCity(UUID cityId, LocalDate date);
}
