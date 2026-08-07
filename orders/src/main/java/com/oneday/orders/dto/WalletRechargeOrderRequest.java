package com.oneday.orders.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Start a wallet recharge: mint a gateway order for {@code amountPaise}. */
public class WalletRechargeOrderRequest {

    // ₹100 min, ₹5,00,000 max per recharge — sane guardrails for the pilot.
    @NotNull @Min(10_000) @Max(50_000_000)
    private Long amountPaise;

    public Long getAmountPaise()        { return amountPaise; }
    public void setAmountPaise(Long v)  { this.amountPaise = v; }
}
