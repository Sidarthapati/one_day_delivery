package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.DeliveryType;

import java.util.UUID;

/**
 * @param serviceable  false if either endpoint is outside M3's grid coverage
 * @param originTileId grid tile of the origin point; stored on Shipment for M5 pickup-DA assignment
 * @param destTileId   grid tile of the destination point; stored for M5/M6 delivery-DA + routing
 * @param deliveryType INTERCITY if origin and dest are in different cities, SAME_CITY otherwise
 */
public record ServiceabilityResult(boolean serviceable, UUID originTileId, UUID destTileId, DeliveryType deliveryType) {}
