package com.oneday.grid.service.osrm;

import com.fasterxml.jackson.annotation.JsonProperty;

// Package-private — not part of the public API.
record OsrmTableResponse(
        @JsonProperty("code") String code,
        @JsonProperty("durations") double[][] durations
) {}
