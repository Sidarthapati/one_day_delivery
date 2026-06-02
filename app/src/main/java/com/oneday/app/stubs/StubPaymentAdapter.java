package com.oneday.app.stubs;

import com.oneday.orders.service.PaymentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
class StubPaymentAdapter implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentAdapter.class);

    @Override
    public void verifySignature(String razorpayOrderId, String razorpayPaymentId, String signature) {
        log.info("[STUB] Razorpay verifySignature skipped for orderId={}", razorpayOrderId);
    }

    @Override
    public void capture(String razorpayPaymentId, long amountPaise) {
        log.info("[STUB] Razorpay capture skipped for paymentId={}", razorpayPaymentId);
    }

    @Override
    public String initiateRefund(String razorpayPaymentId, long amountPaise) {
        String id = "rfnd_STUB" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase();
        log.info("[STUB] Razorpay refund → {} for paymentId={}", id, razorpayPaymentId);
        return id;
    }
}
