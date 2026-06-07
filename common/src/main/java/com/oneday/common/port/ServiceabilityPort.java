package com.oneday.common.port;

import com.oneday.common.port.dto.ServiceabilityQuery;
import com.oneday.common.port.dto.ServiceabilityResult;

/**
 * Implemented by M3 (grid module).
 * M4 calls this once at booking with both legs in a single round-trip.
 * M3 resolves city boundaries, tile assignment, and delivery type internally.
 * Non-serviceable routes are returned as result objects (serviceable=false), not exceptions.
 */
public interface ServiceabilityPort {
    ServiceabilityResult check(ServiceabilityQuery query);
}
