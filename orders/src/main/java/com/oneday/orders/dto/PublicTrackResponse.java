package com.oneday.orders.dto;

/** The white-label tracking payload: the shipment's tracking view plus the merchant's branding. */
public record PublicTrackResponse(ShipmentTrackResponse track, TrackBranding branding) {}
