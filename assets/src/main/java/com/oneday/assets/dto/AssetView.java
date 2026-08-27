package com.oneday.assets.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An asset as the console/app sees it. {@code photoUrls} are short-lived presigned GET URLs, populated
 * only on single-asset reads (null and omitted in list views).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetView(
        UUID id,
        String assetTag,
        String category,
        String assetType,
        String trackingMode,
        String name,
        String description,
        String makeModel,
        String serialNumber,
        String registrationNumber,
        UUID cityId,
        String status,
        String condition,
        String currentHolderType,
        UUID currentHolderId,
        String currentHolderName,
        Instant heldSince,
        boolean ackPending,
        List<String> photoUrls,
        Instant createdAt,
        Instant updatedAt
) {}
