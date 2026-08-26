package com.oneday.dispatch.dto.request;

import java.util.List;

/** Midday-absence custody collect: the parcel barcodes the covering DA scanned when taking the
 *  parcel(s) from the absent DA. Must be non-empty (proves the physical hand-off). */
public record CustodyCollectRequest(
        List<String> parcelScans) {
}
