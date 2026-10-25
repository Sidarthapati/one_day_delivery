package com.oneday.common.port.dto.bgv;

/** Result of opening a BGV case: an opaque {@code vendorRef} the caller stores and polls each check by. */
public record BgvInitiateResult(String vendorRef) {}
