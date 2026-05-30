package com.oneday.orders.service;

import com.oneday.common.domain.enums.DeliveryType;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link DeliveryType} from origin and destination city codes.
 *
 * <p>Pure function — zero DB calls, zero side effects. Safe to call from any
 * context (filter, service, test) without a transaction.</p>
 *
 * <p>Rule: if both city codes are identical (case-insensitive) the shipment
 * is {@link DeliveryType#SAME_CITY}; otherwise {@link DeliveryType#INTERCITY}.</p>
 */
@Component
public class DeliveryTypeResolver {

    /**
     * @param originCityCode 3-letter origin city code (e.g. {@code "BLR"})
     * @param destCityCode   3-letter destination city code (e.g. {@code "BOM"})
     * @return {@link DeliveryType#SAME_CITY} if both codes match ignoring case;
     *         {@link DeliveryType#INTERCITY} otherwise
     */
    public DeliveryType resolve(String originCityCode, String destCityCode) {
        if (originCityCode == null || destCityCode == null) {
            return DeliveryType.INTERCITY;
        }
        return originCityCode.equalsIgnoreCase(destCityCode)
                ? DeliveryType.SAME_CITY
                : DeliveryType.INTERCITY;
    }
}
