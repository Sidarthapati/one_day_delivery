package com.oneday.grid.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ServiceabilityConfig(
        @JsonProperty("city_id") String cityId,
        @JsonProperty("city_name") String cityName,
        @JsonProperty("center_lat") double centerLat,
        @JsonProperty("center_lon") double centerLon,
        @JsonProperty("serviceable_pincodes") List<PincodeEntry> serviceablePincodes
) {
    public record PincodeEntry(
            @JsonProperty("pincode") String pincode,
            @JsonProperty("lat") double lat,
            @JsonProperty("lon") double lon,
            @JsonProperty("locality") String locality
    ) {}
}
