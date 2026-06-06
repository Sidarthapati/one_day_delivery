package com.oneday.common.port.dto;

/**
 * Inputs M4 passes to M3's serviceability check at booking.
 * <p>
 * Coordinates (when present) are the authoritative signal — M3 resolves them to an H3 hex
 * directly, so any point inside a city's grid is serviceable, not just catalogued pincodes.
 * Lat/lon are null for callers that only have pincodes; M3 then falls back to its pincode
 * catalogue.
 */
public record ServiceabilityQuery(
        String originPincode,
        String destPincode,
        Double originLat,
        Double originLon,
        Double destLat,
        Double destLon
) {
    /** Pincode-only query (no map coordinates available). */
    public static ServiceabilityQuery ofPincodes(String originPincode, String destPincode) {
        return new ServiceabilityQuery(originPincode, destPincode, null, null, null, null);
    }
}
