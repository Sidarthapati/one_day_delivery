package com.oneday.dispatch.dto.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DA's cron van handoff: the parcel barcodes scanned, the van, and when. */
public record VanHandoffRequest(
        List<String> parcelScans,
        UUID vanId,
        Instant timestamp) {
}
