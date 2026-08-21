package com.oneday.sla.dto;

import java.util.List;

/**
 * Proactive weather advisories for the control tower: one row per operating city whose weather is
 * adverse right now, with how many open parcels are exposed to it (on a ground leg heading into that
 * city). Surfaces "BOM rain → 12 inbound last-mile at risk" as a single card instead of flooding the
 * board with 12 individual rows.
 */
public record WeatherWatchResponse(List<CityAdvisory> cities) {

    public record CityAdvisory(String city, String condition, double tempC, int exposedCount) {}
}
