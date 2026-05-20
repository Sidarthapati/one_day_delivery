package com.oneday.common.port;

import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;

/**
 * Implemented by M2 (pricing module).
 * M4 calls this once at booking after serviceability is confirmed.
 * M4 computes chargeable weight before calling; M2 applies the rate card and returns
 * the complete pricing breakdown. M4 stores and forwards the result unchanged.
 */
public interface PricingPort {
    QuoteResult computeQuote(QuoteRequest request);
}
