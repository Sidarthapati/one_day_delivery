package com.oneday.common.port.dto;

/**
 * Request for the internal per-parcel cost floor (M2 costing model).
 *
 * @param city city code whose ops costing parameters apply, e.g. "BLR"
 */
public record CostFloorQuery(String city) {}
