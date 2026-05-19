package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.DeliveryType;

import java.time.Instant;
import java.util.UUID;

/**
 * Contextual data M9 needs to compute ETA. Passed alongside shipmentId and currentState.
 *
 * @param originCity       city code of the pickup location
 * @param destCity         city code of the delivery location
 * @param deliveryType     INTERCITY or SAME_CITY — determines which ETA model M9 applies
 * @param bookedAt         when the shipment was created; M9 uses this to find the next feasible flight
 * @param assignedFlightId populated once M9 assigns a flight (AT_ORIGIN_HUB onwards); null before that
 */
public record EtaContext(
        String originCity,
        String destCity,
        DeliveryType deliveryType,
        Instant bookedAt,
        UUID assignedFlightId
) {}
