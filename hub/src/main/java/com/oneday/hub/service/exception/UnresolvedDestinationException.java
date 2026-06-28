package com.oneday.hub.service.exception;

/**
 * A destination parcel could not be resolved to a delivery hex / DA territory at the dest hub
 * (§8.1) — its {@code dest_tile_id} is missing or no ACTIVE M3 territory covers it. The parcel must
 * be escalated (ops), never silently dropped (C17). Maps to HTTP 422.
 */
public class UnresolvedDestinationException extends RuntimeException {
    public UnresolvedDestinationException(String shipmentRef) {
        super("Cannot resolve a delivery territory for parcel " + shipmentRef);
    }
}
