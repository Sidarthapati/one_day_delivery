package com.oneday.orders.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Confirm a wallet recharge after the gateway checkout completes.
 *
 * <p>The credited amount is intentionally NOT taken from the client: it is resolved server-side from
 * the {@code wallet_recharge_order} recorded at order-creation time. Razorpay's signature covers only
 * {@code orderId|paymentId}, so a client-supplied amount cannot be trusted.</p>
 */
public class WalletRechargeConfirmRequest {

    @NotBlank private String razorpayOrderId;
    @NotBlank private String razorpayPaymentId;
    @NotBlank private String signature;

    public String getRazorpayOrderId()          { return razorpayOrderId; }
    public void setRazorpayOrderId(String v)     { this.razorpayOrderId = v; }

    public String getRazorpayPaymentId()         { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v)   { this.razorpayPaymentId = v; }

    public String getSignature()                 { return signature; }
    public void setSignature(String v)           { this.signature = v; }
}
