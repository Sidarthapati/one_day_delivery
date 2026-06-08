package com.oneday.orders.service;

/**
 * Port for Razorpay payment operations.
 *
 * <p>This interface is intentionally local to the {@code orders} module — no other
 * module interacts with Razorpay directly, so it does not belong in {@code common}.
 * The production implementation ({@code RazorpayPaymentAdapter}) will live in
 * {@code orders/service/impl/} and be wired by Spring.</p>
 *
 * <p>All monetary amounts are in <strong>paise</strong> (1 INR = 100 paise).</p>
 */
public interface PaymentPort {

    /**
     * Creates a payment order for the given amount before checkout. The client opens the
     * checkout against the returned {@code orderId}; on success it returns a payment id +
     * signature that {@link #verifySignature} validates.
     *
     * @param amountPaise amount to collect, in paise
     * @param receipt     merchant-side reference (e.g. a booking idempotency key) for audit
     * @return the created order (id, amount, currency, and the public key id for the checkout)
     */
    PaymentOrder createOrder(long amountPaise, String receipt);

    /** A created payment order. {@code keyId} is the public key the checkout UI needs. */
    record PaymentOrder(String orderId, long amountPaise, String currency, String keyId) {}

    /**
     * Verifies the Razorpay HMAC-SHA256 payment signature supplied by the client
     * after the Razorpay checkout JS SDK completes.
     *
     * @param razorpayOrderId   Razorpay order ID created by the client (e.g. {@code order_xxxxxxxx})
     * @param razorpayPaymentId Razorpay payment ID returned by the checkout SDK (e.g. {@code pay_xxxxxxxx})
     * @param signature         HMAC-SHA256 signature provided by the client
     * @throws PaymentVerificationException if the signature does not match
     */
    void verifySignature(String razorpayOrderId, String razorpayPaymentId, String signature);

    /**
     * Captures a previously authorised Razorpay payment.
     *
     * @param razorpayPaymentId the payment to capture
     * @param amountPaise       amount to capture in paise; must match the authorised amount
     * @throws PaymentCaptureException if Razorpay rejects or returns an error
     */
    void capture(String razorpayPaymentId, long amountPaise);

    /**
     * Initiates a refund for a captured Razorpay payment.
     *
     * @param razorpayPaymentId the payment to refund
     * @param amountPaise       refund amount in paise; may be partial
     * @return the Razorpay refund ID (e.g. {@code rfnd_xxxxxxxx}) for audit trail storage
     * @throws PaymentRefundException if Razorpay rejects or returns an error
     */
    String initiateRefund(String razorpayPaymentId, long amountPaise);

    // -------------------------------------------------------------------------
    // Unchecked exceptions (all extend RuntimeException — callers need not declare them)
    // -------------------------------------------------------------------------

    class PaymentVerificationException extends RuntimeException {
        public PaymentVerificationException(String message) { super(message); }
        public PaymentVerificationException(String message, Throwable cause) { super(message, cause); }
    }

    class PaymentCaptureException extends RuntimeException {
        public PaymentCaptureException(String message) { super(message); }
        public PaymentCaptureException(String message, Throwable cause) { super(message, cause); }
    }

    class PaymentRefundException extends RuntimeException {
        public PaymentRefundException(String message) { super(message); }
        public PaymentRefundException(String message, Throwable cause) { super(message, cause); }
    }
}
