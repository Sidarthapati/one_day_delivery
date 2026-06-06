package com.oneday.orders.api;

import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
class OrdersGlobalExceptionHandler {

    @ExceptionHandler(BookingService.InvalidBookingRequestException.class)
    ProblemDetail handleInvalidBookingRequest(BookingService.InvalidBookingRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Invalid request");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(BookingService.ServiceabilityException.class)
    ProblemDetail handleServiceability(BookingService.ServiceabilityException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Route not serviceable");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(PaymentPort.PaymentVerificationException.class)
    ProblemDetail handlePaymentVerification(PaymentPort.PaymentVerificationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        pd.setTitle("Payment verification failed");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(PaymentPort.PaymentCaptureException.class)
    ProblemDetail handlePaymentCapture(PaymentPort.PaymentCaptureException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        pd.setTitle("Payment capture failed");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setTitle("Service temporarily unavailable");
        pd.setDetail("Downstream service is unavailable. Please retry in a few seconds.");
        return pd;
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalStateTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Invalid state transition");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(CancellationService.CancellationNotAllowedException.class)
    ProblemDetail handleCancellationNotAllowed(CancellationService.CancellationNotAllowedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Cancellation not allowed");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> violations = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a));
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Validation failed");
        pd.setProperty("violations", violations);
        return pd;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Missing required header");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Duplicate or constraint violation");
        pd.setDetail("The request conflicts with existing data (e.g. duplicate payment ID).");
        return pd;
    }

    @ExceptionHandler(BookingService.DownstreamTimeoutException.class)
    ProblemDetail handleDownstreamTimeout(BookingService.DownstreamTimeoutException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setTitle("Service temporarily unavailable");
        pd.setDetail("Downstream service call timed out. Please retry in a few seconds.");
        return pd;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleEntityNotFound(EntityNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Resource not found");
        pd.setDetail("The requested shipment does not exist.");
        return pd;
    }

    @ExceptionHandler(B2bBookingService.AccountNotFoundException.class)
    ProblemDetail handleB2bAccountNotFound(B2bBookingService.AccountNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("B2B account not found");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(B2bBookingService.AccountInactiveException.class)
    ProblemDetail handleB2bAccountInactive(B2bBookingService.AccountInactiveException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("B2B account inactive");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(B2bBookingService.CreditLimitExceededException.class)
    ProblemDetail handleCreditLimitExceeded(B2bBookingService.CreditLimitExceededException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
        pd.setTitle("Credit limit exceeded");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(B2bBookingService.AccountAccessException.class)
    ProblemDetail handleB2bAccountAccess(B2bBookingService.AccountAccessException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("B2B account access denied");
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
