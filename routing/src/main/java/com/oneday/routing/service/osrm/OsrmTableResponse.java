package com.oneday.routing.service.osrm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Subset of OSRM {@code /table} we read: status code + the duration matrix (seconds). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OsrmTableResponse(String code, double[][] durations) {}
