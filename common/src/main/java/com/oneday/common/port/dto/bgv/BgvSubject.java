package com.oneday.common.port.dto.bgv;

/** The identity data a BGV vendor verifies. Vendor-neutral — a real adapter maps this to its own API. */
public record BgvSubject(
        String fullName,
        String pan,
        String drivingLicense,
        String aadhaar,
        String dob,
        String address
) {}
