package com.oneday.orders.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Confirm a wallet recharge after the gateway checkout completes. */
public class WalletRechargeConfirmRequest {

    @NotBlank private String razorpayOrderId;
    @NotBlank private String razorpayPaymentId;
    @NotBlank private String signature;
    @NotNull @Min(10_000) @Max(50_000_000) private Long amountPaise;

    public String getRazorpayOrderId()          { return razorpayOrderId; }
    public void setRazorpayOrderId(String v)     { this.razorpayOrderId = v; }

    public String getRazorpayPaymentId()         { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v)   { this.razorpayPaymentId = v; }

    public String getSignature()                 { return signature; }
    public void setSignature(String v)           { this.signature = v; }

    public Long getAmountPaise()                 { return amountPaise; }
    public void setAmountPaise(Long v)           { this.amountPaise = v; }
}
