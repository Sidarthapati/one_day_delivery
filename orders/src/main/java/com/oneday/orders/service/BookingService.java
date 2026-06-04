package com.oneday.orders.service;

import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.BookingResponse;

/**
 * Orchestrates a B2C shipment booking end-to-end for all supported payment modes
 * (PREPAID and COD). Flow: serviceability → pricing → payment capture (PREPAID only)
 * → DB persist → best-effort ETA.
 */
public interface BookingService {

    /**
     * Books a B2C shipment.
     * <p>
     * For PREPAID: validates Razorpay fields, verifies signature, and captures payment
     * before persisting. A compensating refund is initiated if the DB write fails.
     * <p>
     * For COD: skips all payment steps; no {@code PaymentTransaction} row is written.
     *
     * @param request        validated booking request from the REST layer
     * @param idempotencyKey value from the {@code Idempotency-Key} header
     * @param userId         authenticated user ID extracted from the JWT / auth header
     * @return booking confirmation with shipment ref, price, and best-effort ETA
     * @throws ServiceabilityException      if the origin/dest pincodes are outside M3 grid coverage
     * @throws InvalidBookingRequestException if PREPAID Razorpay fields are missing or blank
     */
    BookingResponse book(BookingRequest request, String idempotencyKey, String userId);

    class ServiceabilityException extends RuntimeException {
        public ServiceabilityException(String message) { super(message); }
    }

    class DownstreamTimeoutException extends RuntimeException {
        public DownstreamTimeoutException(String message, Throwable cause) { super(message, cause); }
    }

    class InvalidBookingRequestException extends RuntimeException {
        public InvalidBookingRequestException(String message) { super(message); }
    }
}
