package com.oneday.orders.service;

import com.oneday.orders.dto.B2bBookingRequest;
import com.oneday.orders.dto.BookingResponse;

public interface B2bBookingService {

    BookingResponse book(B2bBookingRequest request, String idempotencyKey, String userId);

    class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String message) { super(message); }
    }

    class AccountInactiveException extends RuntimeException {
        public AccountInactiveException(String message) { super(message); }
    }

    class CreditLimitExceededException extends RuntimeException {
        public CreditLimitExceededException(String message) { super(message); }
    }

    /** Caller is authenticated but does not own the requested B2B account → 403. */
    class AccountAccessException extends RuntimeException {
        public AccountAccessException(String message) { super(message); }
    }
}
