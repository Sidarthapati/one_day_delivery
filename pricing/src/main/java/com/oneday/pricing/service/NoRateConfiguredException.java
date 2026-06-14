package com.oneday.pricing.service;

/**
 * No rate is configured for the requested context (missing city-pair on the active card, or a
 * referenced rate card that does not exist). Surfaced to M4 as 422 — the booking cannot be priced.
 */
public class NoRateConfiguredException extends RuntimeException {
    public NoRateConfiguredException(String message) {
        super(message);
    }
}
