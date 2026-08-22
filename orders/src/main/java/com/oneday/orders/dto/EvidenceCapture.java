package com.oneday.orders.dto;

/**
 * One uploaded evidence photo the app submits for measurement.
 *
 * @param objectKey the key returned by the presign step (must belong to this shipment)
 * @param view      which face it frames: "TOP", "SIDE", or "UNKNOWN"
 */
public record EvidenceCapture(String objectKey, String view) {}
