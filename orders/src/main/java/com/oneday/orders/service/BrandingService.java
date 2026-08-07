package com.oneday.orders.service;

import com.oneday.orders.dto.BrandingRequest;
import com.oneday.orders.dto.TrackBranding;

import java.util.UUID;

/** Read/write a B2B account's white-label branding for the public tracking page. */
public interface BrandingService {

    TrackBranding get(UUID accountId);

    TrackBranding update(UUID accountId, BrandingRequest request);
}
