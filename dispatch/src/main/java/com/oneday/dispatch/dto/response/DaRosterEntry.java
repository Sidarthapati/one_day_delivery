package com.oneday.dispatch.dto.response;

import java.util.UUID;

/**
 * One DA on shift, for the DA-absence picker. Unlike the delivery scorecards (which only surface DAs
 * with last-mile tasks), this lists every DA currently on the clock — so a DA doing only pickups can
 * still be marked absent. {@code stopsPending} = active tasks of any type (pickup / delivery / custody).
 */
public record DaRosterEntry(UUID daId, String daName, int stopsPending) {
}
