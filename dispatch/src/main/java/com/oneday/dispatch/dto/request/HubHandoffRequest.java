package com.oneday.dispatch.dto.request;

import java.time.Instant;
import java.util.List;

/** DA's hub drop (HUB_RETURN city, no van): the parcel barcodes scanned and when. */
public record HubHandoffRequest(
        List<String> parcelScans,
        Instant timestamp) {
}
