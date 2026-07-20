package com.oneday.common.domain.enums;

/**
 * The monitored legs of a parcel's journey (M10, Annexure G). Ordered origin→destination.
 *
 * <p>An INTERCITY shipment traverses all seven; a SAME_CITY shipment traverses only
 * {@code FIRST_MILE → ORIGIN_HUB → LAST_MILE} (the air legs and the second hub collapse away —
 * M10 builds the leg plan per shipment from its {@code DeliveryType}).</p>
 */
public enum SlaLegType {
    FIRST_MILE,      // pickup DA → origin hub in-scan
    ORIGIN_HUB,      // origin hub processing → bag seal
    ORIGIN_AIRPORT,  // hub → airport, tender/screen/stage/load
    AIR,             // wheels-up → landing
    DEST_AIRPORT,    // landing → unload/break/sort/DO/release
    DEST_HUB,        // dest hub processing
    LAST_MILE        // hub out-scan → delivered
}
