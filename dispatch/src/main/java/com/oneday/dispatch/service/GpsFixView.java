package com.oneday.dispatch.service;

import java.time.Instant;

/** One point on a DA's breadcrumb trail, as returned to the app/ops client (no entity internals leaked). */
public record GpsFixView(double lat, double lon, Instant recordedAt) {
}
