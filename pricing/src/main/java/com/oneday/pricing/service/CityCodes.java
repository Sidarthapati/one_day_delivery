package com.oneday.pricing.service;

import java.util.Map;

/**
 * Normalises whatever city token M4 sends (IATA-style code or display name) to the canonical
 * 3-letter code the rate matrix is keyed on. M4 currently sends codes (BLR/DEL/BOM/MAA/HYD), but
 * aliasing the full names keeps pricing robust if a caller sends "Bengaluru" or "Bangalore".
 */
public final class CityCodes {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("BANGALORE", "BLR"),
            Map.entry("BENGALURU", "BLR"),
            Map.entry("DELHI", "DEL"),
            Map.entry("NEWDELHI", "DEL"),
            Map.entry("MUMBAI", "BOM"),
            Map.entry("BOMBAY", "BOM"),
            Map.entry("HYDERABAD", "HYD"),
            Map.entry("CHENNAI", "MAA"),
            Map.entry("KOLKATA", "CCU"),
            Map.entry("CALCUTTA", "CCU"),
            Map.entry("BHUBANESWAR", "BBI"),
            Map.entry("CHANDIGARH", "IXC"),
            Map.entry("GUWAHATI", "GAU")
    );

    private CityCodes() {}

    /** Canonical upper-case city code; pass-through for already-canonical codes. */
    public static String normalise(String city) {
        if (city == null) {
            return null;
        }
        String key = city.trim().toUpperCase().replace(" ", "");
        return ALIASES.getOrDefault(key, key);
    }
}
