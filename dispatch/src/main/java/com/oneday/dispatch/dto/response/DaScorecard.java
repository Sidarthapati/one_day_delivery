package com.oneday.dispatch.dto.response;

import java.util.UUID;

/**
 * One DA's delivery performance for a date — the control-tower scorecard row.
 *
 * @param stopsDone         completed delivery stops
 * @param stopsFailed       failed delivery attempts
 * @param stopsPending      still-open delivery tasks
 * @param stopsPerHour      completed stops ÷ hours on shift (since first assignment); 0 for a fresh DA
 * @param attemptSuccessPct completed ÷ (completed + failed) attempts, 0..1; null when no attempts yet
 * @param onTimePct         completed on/before ETA ÷ completed, 0..1; null when nothing completed
 */
public record DaScorecard(
        UUID daId,
        String daName,
        String daPhone,
        long stopsDone,
        long stopsFailed,
        long stopsPending,
        double stopsPerHour,
        Double attemptSuccessPct,
        Double onTimePct) {
}
