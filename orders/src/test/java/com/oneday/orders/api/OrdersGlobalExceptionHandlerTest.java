package com.oneday.orders.api;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.AddressBookService;
import com.oneday.orders.service.B2bBookingService;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.BulkUploadService;
import com.oneday.orders.service.CancellationService;
import com.oneday.orders.service.CartService;
import com.oneday.orders.service.CashHandoffOtpService;
import com.oneday.orders.service.MerchantCategoryService;
import com.oneday.orders.service.OrderCapacityService;
import com.oneday.orders.service.OrderRepairService;
import com.oneday.orders.service.PaymentPort;
import com.oneday.orders.service.PickupSlotFullException;
import com.oneday.orders.service.WalletService;
import com.oneday.orders.service.exception.IllegalStateTransitionException;
import com.oneday.pricing.service.NoRateConfiguredException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the {@link OrdersGlobalExceptionHandler}: each handler is a pure function
 * mapping an exception to a {@link ProblemDetail}, so we invoke them directly (no Spring context).
 */
class OrdersGlobalExceptionHandlerTest {

    private final OrdersGlobalExceptionHandler handler = new OrdersGlobalExceptionHandler();

    private static void assertPd(ProblemDetail pd, HttpStatus status, String title) {
        assertThat(pd.getStatus()).isEqualTo(status.value());
        assertThat(pd.getTitle()).isEqualTo(title);
    }

    @Test
    void invalidBookingRequest_is422() {
        ProblemDetail pd = handler.handleInvalidBookingRequest(
                new BookingService.InvalidBookingRequestException("bad"));
        assertPd(pd, HttpStatus.UNPROCESSABLE_ENTITY, "Invalid request");
        assertThat(pd.getDetail()).isEqualTo("bad");
    }

    @Test
    void serviceability_is422() {
        assertPd(handler.handleServiceability(new BookingService.ServiceabilityException("x")),
                HttpStatus.UNPROCESSABLE_ENTITY, "Route not serviceable");
    }

    @Test
    void handoffOtp_is422() {
        assertPd(handler.handleHandoffOtp(new CashHandoffOtpService.OtpVerificationException("x")),
                HttpStatus.UNPROCESSABLE_ENTITY, "Cash handoff not verified");
    }

    @Test
    void noRateConfigured_is422() {
        assertPd(handler.handleNoRateConfigured(new NoRateConfiguredException("x")),
                HttpStatus.UNPROCESSABLE_ENTITY, "Route not priceable");
    }

    @Test
    void addressNotFound_is404() {
        assertPd(handler.handleAddressNotFound(new AddressBookService.AddressNotFoundException("x")),
                HttpStatus.NOT_FOUND, "Address not found");
    }

    @Test
    void cartItemNotFound_is404() {
        assertPd(handler.handleCartItemNotFound(new CartService.CartItemNotFoundException("x")),
                HttpStatus.NOT_FOUND, "Cart item not found");
    }

    @Test
    void cartCheckout_emptyAndValidation_is422() {
        assertPd(handler.handleCartCheckout(new CartService.EmptyCartException("x")),
                HttpStatus.UNPROCESSABLE_ENTITY, "Cart checkout not possible");
        assertPd(handler.handleCartCheckout(new CartService.CheckoutValidationException("y")),
                HttpStatus.UNPROCESSABLE_ENTITY, "Cart checkout not possible");
    }

    @Test
    void badTemplate_is422() {
        assertPd(handler.handleBadTemplate(new BulkUploadService.BadTemplateException("x")),
                HttpStatus.UNPROCESSABLE_ENTITY, "Invalid upload");
    }

    @Test
    void paymentVerification_is402() {
        assertPd(handler.handlePaymentVerification(new PaymentPort.PaymentVerificationException("x")),
                HttpStatus.PAYMENT_REQUIRED, "Payment verification failed");
    }

    @Test
    void paymentCapture_is402() {
        assertPd(handler.handlePaymentCapture(new PaymentPort.PaymentCaptureException("x")),
                HttpStatus.PAYMENT_REQUIRED, "Payment capture failed");
    }

    @Test
    void insufficientWallet_is402() {
        ProblemDetail pd = handler.handleInsufficientWallet(
                new WalletService.InsufficientWalletBalanceException("x"));
        assertPd(pd, HttpStatus.PAYMENT_REQUIRED, "Insufficient wallet balance");
        assertThat(pd.getDetail()).contains("Recharge");
    }

    @Test
    void circuitOpen_is503() {
        assertPd(handler.handleCircuitOpen(mock(CallNotPermittedException.class)),
                HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
    }

    @Test
    void illegalTransition_is409() {
        assertPd(handler.handleIllegalTransition(
                        new IllegalStateTransitionException(ShipmentState.BOOKED, ShipmentState.DROPPED)),
                HttpStatus.CONFLICT, "Invalid state transition");
    }

    @Test
    void cancellationNotAllowed_is409() {
        assertPd(handler.handleCancellationNotAllowed(
                        new CancellationService.CancellationNotAllowedException("x")),
                HttpStatus.CONFLICT, "Cancellation not allowed");
    }

    @Test
    void pickupSlotFull_is409() {
        assertPd(handler.handlePickupSlotFull(new PickupSlotFullException("x")),
                HttpStatus.CONFLICT, "Pickup slot full");
    }

    @Test
    void daCapacityExceeded_is409() {
        assertPd(handler.handleDaCapacityExceeded(new OrderCapacityService.DaCapacityExceededException("x")),
                HttpStatus.CONFLICT, "Delivery associate at capacity");
    }

    @Test
    void orderNotEditable_is409() {
        assertPd(handler.handleOrderNotEditable(new OrderRepairService.OrderNotEditableException("x")),
                HttpStatus.CONFLICT, "Order not editable");
    }

    @Test
    void categoryNotFound_is404() {
        assertPd(handler.handleCategoryNotFound(new MerchantCategoryService.CategoryNotFoundException("x")),
                HttpStatus.NOT_FOUND, "Category not found");
    }

    @Test
    void duplicateCategory_is409() {
        assertPd(handler.handleDuplicateCategory(new MerchantCategoryService.DuplicateCategoryException("x")),
                HttpStatus.CONFLICT, "Duplicate category");
    }

    @Test
    void validation_collectsViolations_withMergeAndNullDefault() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
                new FieldError("o", "f1", "must not be null"),
                new FieldError("o", "f1", "duplicate field — merge keeps first"),
                new FieldError("o", "f2", null))); // null default → "invalid"
        ProblemDetail pd = handler.handleValidation(ex);
        assertPd(pd, HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        @SuppressWarnings("unchecked")
        Map<String, String> v = (Map<String, String>) pd.getProperties().get("violations");
        assertThat(v).containsEntry("f1", "must not be null").containsEntry("f2", "invalid");
    }

    @Test
    void missingHeader_is400() {
        assertPd(handler.handleMissingHeader(mock(MissingRequestHeaderException.class)),
                HttpStatus.BAD_REQUEST, "Missing required header");
    }

    @Test
    void dataIntegrity_is409() {
        assertPd(handler.handleDataIntegrity(new DataIntegrityViolationException("dup")),
                HttpStatus.CONFLICT, "Duplicate or constraint violation");
    }

    @Test
    void downstreamTimeout_is503() {
        assertPd(handler.handleDownstreamTimeout(
                        new BookingService.DownstreamTimeoutException("x", new RuntimeException())),
                HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable");
    }

    @Test
    void entityNotFound_is404() {
        assertPd(handler.handleEntityNotFound(new EntityNotFoundException("x")),
                HttpStatus.NOT_FOUND, "Resource not found");
    }

    @Test
    void b2bAccountNotFound_is404() {
        assertPd(handler.handleB2bAccountNotFound(new B2bBookingService.AccountNotFoundException("x")),
                HttpStatus.NOT_FOUND, "B2B account not found");
    }

    @Test
    void b2bAccountInactive_is409() {
        assertPd(handler.handleB2bAccountInactive(new B2bBookingService.AccountInactiveException("x")),
                HttpStatus.CONFLICT, "B2B account inactive");
    }

    @Test
    void creditLimitExceeded_is402() {
        assertPd(handler.handleCreditLimitExceeded(new B2bBookingService.CreditLimitExceededException("x")),
                HttpStatus.PAYMENT_REQUIRED, "Credit limit exceeded");
    }

    @Test
    void b2bAccountAccess_is403() {
        assertPd(handler.handleB2bAccountAccess(new B2bBookingService.AccountAccessException("x")),
                HttpStatus.FORBIDDEN, "B2B account access denied");
    }

    @Test
    void memberBudgetExceeded_is402() {
        assertPd(handler.handleMemberBudgetExceeded(new B2bBookingService.MemberBudgetExceededException("x")),
                HttpStatus.PAYMENT_REQUIRED, "Member spend budget exceeded");
    }

    @Test
    void responseStatus_mirrorsStatus_withAndWithoutReason() {
        ProblemDetail withReason = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "nope"));
        assertPd(withReason, HttpStatus.FORBIDDEN, "Request rejected");
        assertThat(withReason.getDetail()).isEqualTo("nope");

        ProblemDetail noReason = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertPd(noReason, HttpStatus.UNAUTHORIZED, "Request rejected");
    }
}
