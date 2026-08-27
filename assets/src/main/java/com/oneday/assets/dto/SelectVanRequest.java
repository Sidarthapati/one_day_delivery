package com.oneday.assets.dto;

import java.util.UUID;

/**
 * A DA picks the van they're using for the shift: by {@code assetId} (from the available list) or by
 * typing its {@code registrationNumber}. Exactly one should be set; assetId wins if both are present.
 */
public record SelectVanRequest(UUID assetId, String registrationNumber) {}
