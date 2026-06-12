package com.oneday.orders.service;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.port.dto.QuoteResult;
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

    /**
     * Books a retail shipment with an explicit customer type. The b2c endpoint serves both
     * {@code B2C_CUSTOMER} (→ {@link CustomerType#B2C}) and {@code C2C_CUSTOMER}
     * (→ {@link CustomerType#C2C}); the controller derives the type from the caller's role so the
     * persisted shipment (and the response) reflect what the customer actually is — not a hardcoded
     * default. The 3-arg overload delegates here with {@link CustomerType#B2C}.
     */
    BookingResponse book(BookingRequest request, String idempotencyKey, String userId, CustomerType customerType);

    /**
     * Books a retail shipment whose payment has already been settled once at the cart level
     * (the B2C aggregate Razorpay capture). Runs serviceability → pricing → persist, marks the
     * shipment PREPAID, but writes <b>no</b> per-shipment {@code PaymentTransaction} and performs
     * no gateway call. Used by cart checkout; re-prices the request so cart staleness is caught.
     *
     * @throws ServiceabilityException if the route is no longer serviceable at checkout time
     */
    BookingResponse bookSettled(BookingRequest request, String idempotencyKey, String userId, CustomerType customerType);

    /**
     * Prices a request without booking or taking payment (serviceability → pricing).
     * Used by the payment flow to mint a gateway order for the exact amount before checkout.
     *
     * @throws ServiceabilityException if the route is outside M3 grid coverage
     */
    QuoteResult quote(BookingRequest request);

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
