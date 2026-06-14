package com.oneday.pricing.service;

import com.oneday.common.port.CostingPort;

/**
 * Internal costing model (M2-D-004). Computes the per-parcel cost floor from per-city ops params.
 * Marker extension of {@link CostingPort} so M5/M6 depend only on the common contract.
 */
public interface CostingService extends CostingPort {
}
