package com.oneday.assets.dto;

import com.oneday.assets.domain.AssetCategory;
import com.oneday.assets.domain.AssetCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Register a new asset. {@code cityId} is optional — a STATION_MANAGER is pinned to their own city;
 * ADMIN must supply it. {@code photoKeys} are the R2 object keys returned by the photo-upload-urls
 * presign step (empty/null when R2 is unconfigured — registration still succeeds).
 */
public record RegisterAssetRequest(
        @NotBlank String assetTag,
        @NotNull AssetCategory category,
        @NotBlank String assetType,
        @NotBlank String name,
        String description,
        String makeModel,
        String serialNumber,
        String registrationNumber,
        UUID cityId,
        AssetCondition condition,
        List<String> photoKeys
) {}
