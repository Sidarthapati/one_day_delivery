package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * DA vehicle carrying capacity, used to gate adding a parcel to an in-progress pickup (order repair).
 * There is no per-DA vehicle model yet, so this is a single fleet-wide weight limit with optional
 * per-city overrides (keyed by origin city code, upper-cased). Default 50 kg.
 */
@Component
@ConfigurationProperties(prefix = "orders.capacity")
public class OrderCapacityProperties {

    /** Default DA vehicle capacity in grams (50 kg). */
    private int daVehicleGrams = 50_000;

    /** Optional per-city overrides, key = origin city code (e.g. "DELHI"), value = capacity in grams. */
    private Map<String, Integer> daVehicleGramsByCity = new HashMap<>();

    /** The vehicle capacity (grams) for a city — its override if present, else the fleet default. */
    public int capacityGramsFor(String cityCode) {
        if (cityCode == null) {
            return daVehicleGrams;
        }
        return daVehicleGramsByCity.getOrDefault(cityCode.toUpperCase(), daVehicleGrams);
    }

    public int getDaVehicleGrams() { return daVehicleGrams; }
    public void setDaVehicleGrams(int daVehicleGrams) { this.daVehicleGrams = daVehicleGrams; }

    public Map<String, Integer> getDaVehicleGramsByCity() { return daVehicleGramsByCity; }
    public void setDaVehicleGramsByCity(Map<String, Integer> daVehicleGramsByCity) {
        this.daVehicleGramsByCity = daVehicleGramsByCity;
    }
}
