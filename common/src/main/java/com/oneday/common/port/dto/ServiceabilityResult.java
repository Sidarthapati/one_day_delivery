package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.DeliveryType;

import java.util.UUID;

/**
 * @param serviceable  false if either pincode is outside M3's grid coverage
 * @param originTileId grid tile of the origin pincode; stored on Shipment for M5 DA assignment
 * @param deliveryType INTERCITY if origin and dest are in different cities, SAME_CITY otherwise
 */
public record ServiceabilityResult(boolean serviceable, UUID originTileId, DeliveryType deliveryType) {}
