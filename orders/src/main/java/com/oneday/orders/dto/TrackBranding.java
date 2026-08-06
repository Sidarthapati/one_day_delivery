package com.oneday.orders.dto;

import com.oneday.orders.domain.B2bAccount;

/** Merchant branding shown on the white-label tracking page. */
public record TrackBranding(
        String brandName,
        String brandColor,
        String brandLogoUrl,
        String supportEmail,
        String supportPhone) {

    public static TrackBranding from(B2bAccount a) {
        if (a == null) return empty();
        String name = a.getBrandName() != null ? a.getBrandName() : a.getAccountName();
        return new TrackBranding(name, a.getBrandColor(), a.getBrandLogoUrl(),
                a.getSupportEmail(), a.getSupportPhone());
    }

    public static TrackBranding empty() {
        return new TrackBranding(null, null, null, null, null);
    }
}
