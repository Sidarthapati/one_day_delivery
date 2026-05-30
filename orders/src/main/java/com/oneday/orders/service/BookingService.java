package com.oneday.orders.service;

import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;

/**
 * Orchestrates a B2C PREPAID shipment booking end-to-end:
 * serviceability → pricing → payment capture → DB persist → best-effort ETA.
 */
public interface BookingService {

    /**
     * Books a B2C PREPAID shipment.
     *
     * @param request        validated booking request from the REST layer
     * @param idempotencyKey value from the {@code Idempotency-Key} header
     * @param userId         authenticated user ID extracted from the JWT / auth header
     * @return booking confirmation with shipment ref, price, and best-effort ETA
     * @throws ServiceabilityException if the origin/dest pincodes are outside M3 grid coverage
     */
    BookingResponse book(BookingRequest request, String idempotencyKey, String userId);

    class ServiceabilityException extends RuntimeException {
        public ServiceabilityException(String message) { super(message); }
    }

    class DownstreamTimeoutException extends RuntimeException {
        public DownstreamTimeoutException(String message, Throwable cause) { super(message, cause); }
    }
}
