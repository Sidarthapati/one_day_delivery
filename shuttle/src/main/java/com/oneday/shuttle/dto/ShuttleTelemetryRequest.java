package com.oneday.shuttle.dto;

/** A GPS ping from the shuttle-agent app. Pure position — no stops/lateness (that's the van's world). */
public record ShuttleTelemetryRequest(double lat, double lon) {
}
