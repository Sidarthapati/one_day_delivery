package com.oneday.app.stubs;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.port.ServiceabilityPort;
import com.oneday.common.port.dto.ServiceabilityResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Profile("!prod")
class StubServiceabilityAdapter implements ServiceabilityPort {

    private static final Map<String, String> PINCODE_TO_CITY = Map.ofEntries(
            Map.entry("560001", "BLR"), Map.entry("560034", "BLR"), Map.entry("560100", "BLR"),
            Map.entry("400001", "BOM"), Map.entry("400051", "BOM"), Map.entry("400099", "BOM"),
            Map.entry("110001", "DEL"), Map.entry("110011", "DEL"), Map.entry("110062", "DEL"),
            Map.entry("600001", "MAA"), Map.entry("600028", "MAA"), Map.entry("600091", "MAA"),
            Map.entry("500001", "HYD"), Map.entry("500016", "HYD"), Map.entry("500072", "HYD")
    );

    private static final Map<String, UUID> CITY_TO_TILE = Map.of(
            "BLR", UUID.fromString("b1a00000-0000-0000-0000-000000000001"),
            "BOM", UUID.fromString("b0b00000-0000-0000-0000-000000000002"),
            "DEL", UUID.fromString("de100000-0000-0000-0000-000000000003"),
            "MAA", UUID.fromString("4aa00000-0000-0000-0000-000000000004"),
            "HYD", UUID.fromString("5de00000-0000-0000-0000-000000000005")
    );

    @Override
    public ServiceabilityResult check(String originPincode, String destPincode) {
        String originCity = PINCODE_TO_CITY.get(originPincode);
        String destCity   = PINCODE_TO_CITY.get(destPincode);

        if (originCity == null || destCity == null) {
            return new ServiceabilityResult(false, null, null);
        }

        UUID tile = CITY_TO_TILE.getOrDefault(originCity,
                UUID.fromString("00000000-0000-0000-0000-000000000000"));
        DeliveryType type = originCity.equals(destCity)
                ? DeliveryType.SAME_CITY
                : DeliveryType.INTERCITY;

        return new ServiceabilityResult(true, tile, type);
    }
}
