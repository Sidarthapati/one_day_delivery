package com.oneday.common.port;

import com.oneday.common.port.dto.CostFloorQuery;
import com.oneday.common.port.dto.CostFloorResult;

/**
 * Implemented by M2 (pricing/costing module).
 *
 * <p>Exposes the internal per-parcel <b>cost floor</b> — the marginal fulfilment cost used by
 * scheduling algorithms (M5 DA assignment, M6 van routing) to evaluate feasibility. This is an
 * internal figure and is never surfaced to customers (see decision M2-D-004).</p>
 */
public interface CostingPort {
    CostFloorResult computeCostFloor(CostFloorQuery query);
}
