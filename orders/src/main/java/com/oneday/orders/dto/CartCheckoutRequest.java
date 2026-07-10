package com.oneday.orders.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Checkout payload.
 * <ul>
 *   <li><b>B2C / C2C:</b> one Razorpay order/payment covering the whole-cart total — the three
 *       razorpay* fields are required and verified once before the items are booked.</li>
 *   <li><b>B2B:</b> {@code b2bAccountId} is required; items are booked against the account's
 *       credit line (no gateway). The razorpay* fields are ignored.</li>
 * </ul>
 * The lane is derived from the caller's role, not from this payload.
 */
public class CartCheckoutRequest {

    @Size(max = 100) private String razorpayOrderId;
    @Size(max = 100) private String razorpayPaymentId;
    @Size(max = 500) private String razorpaySignature;

    private UUID b2bAccountId;

    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String v) { this.razorpayOrderId = v; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v) { this.razorpayPaymentId = v; }
    public String getRazorpaySignature() { return razorpaySignature; }
    public void setRazorpaySignature(String v) { this.razorpaySignature = v; }
    public UUID getB2bAccountId() { return b2bAccountId; }
    public void setB2bAccountId(UUID v) { this.b2bAccountId = v; }
}
