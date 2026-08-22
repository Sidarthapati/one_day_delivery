package com.oneday.orders.dto;

/**
 * A presigned upload slot: the app PUTs one photo's bytes directly to {@code uploadUrl}, then passes
 * {@code objectKey} back when submitting the measurement. Keys are chosen server-side.
 */
public record EvidenceUpload(String objectKey, String uploadUrl) {}
