package com.oneday.orders.service;

import com.oneday.common.domain.enums.DeliveryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryTypeResolverTest {

    private final DeliveryTypeResolver resolver = new DeliveryTypeResolver();

    @Test
    void sameCityCode_returnsSameCity() {
        assertThat(resolver.resolve("BLR", "BLR")).isEqualTo(DeliveryType.SAME_CITY);
    }

    @Test
    void sameCityCode_caseInsensitive() {
        assertThat(resolver.resolve("blr", "BLR")).isEqualTo(DeliveryType.SAME_CITY);
        assertThat(resolver.resolve("BLR", "blr")).isEqualTo(DeliveryType.SAME_CITY);
        assertThat(resolver.resolve("blr", "blr")).isEqualTo(DeliveryType.SAME_CITY);
    }

    @Test
    void differentCityCodes_returnsIntercity() {
        assertThat(resolver.resolve("BLR", "BOM")).isEqualTo(DeliveryType.INTERCITY);
        assertThat(resolver.resolve("DEL", "MAA")).isEqualTo(DeliveryType.INTERCITY);
    }

    @Test
    void nullOrigin_returnsIntercity() {
        assertThat(resolver.resolve(null, "BLR")).isEqualTo(DeliveryType.INTERCITY);
    }

    @Test
    void nullDest_returnsIntercity() {
        assertThat(resolver.resolve("BLR", null)).isEqualTo(DeliveryType.INTERCITY);
    }

    @Test
    void bothNull_returnsIntercity() {
        assertThat(resolver.resolve(null, null)).isEqualTo(DeliveryType.INTERCITY);
    }
}
