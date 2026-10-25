package com.oneday.auth.dto.response;

import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import com.oneday.common.port.dto.bgv.BgvCheckType;

import java.time.Instant;
import java.util.UUID;

/** One BGV check as shown on the reviewer's candidate detail (the IDfy-style Green/Amber/Red card). */
public record BgvCheckResponse(
        UUID id,
        BgvCheckType checkType,
        BgvCheckStatus status,
        String message,
        Instant initiatedAt,
        Instant completedAt
) {}
