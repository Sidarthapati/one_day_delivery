package com.oneday.dispatch.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Control-tower execution view for a city/date: the delivery attempt-success gauge and per-DA pace.
 *
 * @param date               operating date the figures are for
 * @param attemptSuccessPct  delivered / (delivered + failed) delivery attempts, 0..1; null if no attempts
 * @param deliveriesCompleted successful delivery tasks
 * @param deliveriesFailed    failed delivery attempts
 * @param das                per-DA pace, busiest (most pending) first
 */
public record DispatchExecutionStats(
        LocalDate date,
        Double attemptSuccessPct,
        long deliveriesCompleted,
        long deliveriesFailed,
        List<DaPace> das) {

    /**
     * @param daId           the DA
     * @param daName         DA name (null if not in the directory) — so the console shows a person, not a UUID
     * @param daPhone        DA phone for a one-tap call (nullable)
     * @param stopsDone      tasks completed today
     * @param stopsLastHour  completed in the last hour — the current pace
     * @param stopsPending   still-open tasks (queued or in progress)
     * @param avgPerHour     stopsDone over hours since the DA's first assignment (0 if not started)
     */
    public record DaPace(
            UUID daId,
            String daName,
            String daPhone,
            long stopsDone,
            long stopsLastHour,
            long stopsPending,
            double avgPerHour) {
    }
}
