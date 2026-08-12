package com.oneday.shuttle.service;

import java.util.Map;

/**
 * Normalises an agent's {@code users.city_id} to the IATA hub code used by {@code flight_bag.origin_hub}
 * / {@code awb.dest_hub}. Necessary because {@code users.city_id} is mixed in practice — some rows carry
 * the lowercase grid-city name ("delhi"), others already the IATA code ("DEL") — while the hub/airline
 * sides are always IATA. Unknown input is upper-cased and passed through (best-effort).
 */
final class HubCode {

    private static final Map<String, String> BY_NAME = Map.ofEntries(
            Map.entry("delhi", "DEL"), Map.entry("newdelhi", "DEL"), Map.entry("del", "DEL"),
            Map.entry("mumbai", "BOM"), Map.entry("bombay", "BOM"), Map.entry("bom", "BOM"),
            Map.entry("bangalore", "BLR"), Map.entry("bengaluru", "BLR"), Map.entry("blr", "BLR"),
            Map.entry("hyderabad", "HYD"), Map.entry("hyd", "HYD"),
            Map.entry("chennai", "MAA"), Map.entry("madras", "MAA"), Map.entry("maa", "MAA"));

    private HubCode() {
    }

    static String of(String cityId) {
        if (cityId == null || cityId.isBlank()) {
            return cityId;
        }
        String key = cityId.trim().toLowerCase().replace(" ", "");
        return BY_NAME.getOrDefault(key, cityId.trim().toUpperCase());
    }
}
