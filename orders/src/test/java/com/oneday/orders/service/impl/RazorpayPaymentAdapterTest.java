package com.oneday.orders.service.impl;

import com.oneday.orders.config.RazorpayProperties;
import com.oneday.orders.service.PaymentPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the REAL {@link RazorpayPaymentAdapter#verifySignature} + {@link RazorpaySignatures} HMAC —
 * the booking tests mock {@code PaymentPort}, so this is the only place to step into the actual logic.
 * Put a breakpoint in {@code verifySignature} / {@code RazorpaySignatures.sign} and run a method below.
 */
class RazorpayPaymentAdapterTest {

    private static final String ORDER_ID   = "order_1DDtestABC";
    private static final String PAYMENT_ID = "pay_1DDtestXYZ";

    private final RazorpayProperties props = new RazorpayProperties(); // default keySecret, isLive=false
    private final RazorpayPaymentAdapter adapter = new RazorpayPaymentAdapter(props);

    @Test
    void validSignature_passes() {
        // Reproduce what the (mock) checkout / Razorpay would send: HMAC over "order|payment".
        String goodSig = RazorpaySignatures.sign(ORDER_ID, PAYMENT_ID, props.getKeySecret());

        assertThatCode(() -> adapter.verifySignature(ORDER_ID, PAYMENT_ID, goodSig))
                .doesNotThrowAnyException();
    }

    @Test
    void tamperedSignature_throwsVerification() {
        String goodSig = RazorpaySignatures.sign(ORDER_ID, PAYMENT_ID, props.getKeySecret());
        String tampered = goodSig.substring(0, goodSig.length() - 1) + (goodSig.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> adapter.verifySignature(ORDER_ID, PAYMENT_ID, tampered))
                .isInstanceOf(PaymentPort.PaymentVerificationException.class);
    }

    @Test
    void wrongSecret_throwsVerification() {
        // A signature signed with a different secret must not verify against props.getKeySecret().
        String foreignSig = RazorpaySignatures.sign(ORDER_ID, PAYMENT_ID, "some_other_secret");

        assertThatThrownBy(() -> adapter.verifySignature(ORDER_ID, PAYMENT_ID, foreignSig))
                .isInstanceOf(PaymentPort.PaymentVerificationException.class);
    }

    @Test
    void nullSignature_throwsVerification() {
        // matches() returns false for null → mismatch, not NPE.
        assertThatThrownBy(() -> adapter.verifySignature(ORDER_ID, PAYMENT_ID, null))
                .isInstanceOf(PaymentPort.PaymentVerificationException.class);
    }
}
