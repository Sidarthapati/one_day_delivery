package com.oneday.common.port.dto.bgv;

/** The current status of one check when polled, with a human-readable note. */
public record BgvCheckResult(BgvCheckStatus status, String message) {}
