package com.oneday.assets.dto;

/**
 * A presigned upload slot: the console PUTs one photo's bytes directly to {@code uploadUrl}, then passes
 * {@code objectKey} back in the register request's {@code photoKeys}. Keys are chosen server-side.
 */
public record EvidenceUpload(String objectKey, String uploadUrl) {}
