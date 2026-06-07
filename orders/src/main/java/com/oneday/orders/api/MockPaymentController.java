package com.oneday.orders.api;

import com.oneday.orders.config.RazorpayProperties;
import com.oneday.orders.service.impl.RazorpaySignatures;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;

/**
 * Test-gateway stand-in for Razorpay's hosted checkout (active outside prod only). Given an
 * order id it returns a payment id + a real HMAC-SHA256 signature, exactly what Razorpay's
 * servers would hand back — so the booking endpoint's signature verification is genuinely
 * exercised. Never present in prod, where the real Razorpay checkout produces these.
 */
@RestController
@RequestMapping("/api/v1/payments/mock")
@Profile("!prod")
class MockPaymentController {

    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom rng = new SecureRandom();
    private final RazorpayProperties props;

    MockPaymentController(RazorpayProperties props) {
        this.props = props;
    }

    @PostMapping("/pay")
    public MockPayResponse pay(@RequestBody MockPayRequest req) {
        String paymentId = "pay_" + randomId();
        String signature = RazorpaySignatures.sign(req.orderId(), paymentId, props.getKeySecret());
        return new MockPayResponse(req.orderId(), paymentId, signature);
    }

    private String randomId() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) sb.append(ALPHANUM.charAt(rng.nextInt(ALPHANUM.length())));
        return sb.toString();
    }

    record MockPayRequest(String orderId) {}

    record MockPayResponse(String razorpayOrderId, String razorpayPaymentId, String razorpaySignature) {}
}
