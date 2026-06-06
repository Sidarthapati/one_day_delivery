package com.oneday.orders.service.impl;

import com.oneday.orders.config.RazorpayProperties;
import com.oneday.orders.service.PaymentPort;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Refund;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Razorpay payment adapter. Signature verification uses the production HMAC-SHA256 scheme
 * in all modes. With {@code razorpay.live=false} (default) orders are minted locally and
 * capture/refund are no-ops — a self-contained test gateway needing no Razorpay account.
 * With {@code razorpay.live=true} it calls the Razorpay API via the SDK (works with both
 * test {@code rzp_test_} and live {@code rzp_live_} keys; orders auto-capture on success).
 */
@Component
class RazorpayPaymentAdapter implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentAdapter.class);
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom rng = new SecureRandom();

    private final RazorpayProperties props;
    private volatile RazorpayClient client;

    RazorpayPaymentAdapter(RazorpayProperties props) {
        this.props = props;
    }

    @Override
    public PaymentOrder createOrder(long amountPaise, String receipt) {
        if (props.isLive()) {
            try {
                JSONObject req = new JSONObject()
                        .put("amount", amountPaise)
                        .put("currency", "INR")
                        .put("receipt", receipt)
                        .put("payment_capture", true);  // auto-capture on successful payment
                Order order = liveClient().orders.create(req);
                String orderId = order.get("id");
                log.info("[razorpay:live] created order {} for {} paise", orderId, amountPaise);
                return new PaymentOrder(orderId, amountPaise, "INR", props.getKeyId());
            } catch (RazorpayException e) {
                throw new PaymentCaptureException("Razorpay order creation failed: " + e.getMessage(), e);
            }
        }
        String orderId = "order_" + randomId(14);
        log.info("[razorpay:test] created order {} for {} paise (receipt={})", orderId, amountPaise, receipt);
        return new PaymentOrder(orderId, amountPaise, "INR", props.getKeyId());
    }

    @Override
    public void verifySignature(String razorpayOrderId, String razorpayPaymentId, String signature) {
        String expected = RazorpaySignatures.sign(razorpayOrderId, razorpayPaymentId, props.getKeySecret());
        if (!RazorpaySignatures.matches(expected, signature)) {
            throw new PaymentVerificationException(
                    "Razorpay signature mismatch for orderId=" + razorpayOrderId);
        }
        log.info("[razorpay] signature verified for orderId={} paymentId={}", razorpayOrderId, razorpayPaymentId);
    }

    @Override
    public void capture(String razorpayPaymentId, long amountPaise) {
        // Orders are created with payment_capture=true, so Razorpay captures on success.
        // Nothing to do here in either mode beyond an audit log.
        log.info("[razorpay:{}] payment {} captured ({} paise)",
                props.isLive() ? "live" : "test", razorpayPaymentId, amountPaise);
    }

    @Override
    public String initiateRefund(String razorpayPaymentId, long amountPaise) {
        if (props.isLive()) {
            try {
                Refund refund = liveClient().payments.refund(
                        razorpayPaymentId, new JSONObject().put("amount", amountPaise));
                String refundId = refund.get("id");
                log.info("[razorpay:live] refund {} → {} paise on paymentId={}", refundId, amountPaise, razorpayPaymentId);
                return refundId;
            } catch (RazorpayException e) {
                throw new PaymentRefundException("Razorpay refund failed: " + e.getMessage(), e);
            }
        }
        String refundId = "rfnd_" + randomId(14);
        log.info("[razorpay:test] refund {} → {} paise on paymentId={}", refundId, amountPaise, razorpayPaymentId);
        return refundId;
    }

    private RazorpayClient liveClient() {
        RazorpayClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        client = new RazorpayClient(props.getKeyId(), props.getKeySecret());
                    } catch (RazorpayException e) {
                        throw new PaymentCaptureException("Razorpay client init failed: " + e.getMessage(), e);
                    }
                }
                c = client;
            }
        }
        return c;
    }

    private String randomId(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHANUM.charAt(rng.nextInt(ALPHANUM.length())));
        return sb.toString();
    }
}
