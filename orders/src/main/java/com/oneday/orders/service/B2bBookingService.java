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
}
