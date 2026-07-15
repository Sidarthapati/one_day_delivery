package com.oneday.common.port;

import java.time.Instant;
import java.util.UUID;

/**
 * A live GPS point for whatever is currently carrying a parcel (a DA or a van). Returned by the
 * live-position ports so the tracking read path (M4) can plot a moving marker without importing the
 * dispatch/routing modules. {@code lastSeenAt} lets the reader decide whether the fix is fresh
 * enough to show as "live" or has gone stale and should fall back to a static node.
 *
 * @param minutesLate lateness vs plan where the source tracks it (van); null for a DA
 * @param sourceId    the da/van id the fix came from (diagnostics only)
 */
public record LivePosition(
        double lat,
        double lon,
        Instant lastSeenAt,
        Integer minutesLate,
        UUID sourceId) {
}
