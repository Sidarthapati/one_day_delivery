package com.oneday.barcode.api;

import java.util.UUID;

/** The minted (or existing) barcode for the shipment — the DA app renders/prints {@code parcelId}. */
public record LabelResponse(UUID shipmentId, String parcelId) {
}
