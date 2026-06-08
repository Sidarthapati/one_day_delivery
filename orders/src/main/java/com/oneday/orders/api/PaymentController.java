package com.oneday.orders.api;

import com.oneday.common.port.dto.QuoteResult;
import com.oneday.orders.config.RazorpayProperties;
import com.oneday.orders.dto.BookingRequest;
import com.oneday.orders.dto.PaymentOrderResponse;
import com.oneday.orders.service.BookingService;
import com.oneday.orders.service.PaymentPort;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment order creation for PREPAID bookings. The client prices + mints a gateway order
 * here, opens checkout, then posts the booking with the resulting payment id + signature
 * (which {@code /api/v1/b2c/shipments} verifies before capture).
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private final BookingService bookingService;
    private final PaymentPort paymentPort;
    private final RazorpayProperties razorpayProps;

    PaymentController(BookingService bookingService, PaymentPort paymentPort, RazorpayProperties razorpayProps) {
        this.bookingService = bookingService;
        this.paymentPort = paymentPort;
        this.razorpayProps = razorpayProps;
    }

    @PostMapping("/order")
    public PaymentOrderResponse createOrder(@Valid @RequestBody BookingRequest req) {
        QuoteResult quote = bookingService.quote(req);
        PaymentPort.PaymentOrder order = paymentPort.createOrder(quote.totalPricePaise(), "shipment-booking");
        return new PaymentOrderResponse(
                order.orderId(), order.amountPaise(), order.currency(), order.keyId(), !razorpayProps.isLive());
    }
}
