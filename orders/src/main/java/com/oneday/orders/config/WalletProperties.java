package com.oneday.orders.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * B2B prepaid-wallet config.
 *
 * <pre>{@code
 * orders:
 *   wallet:
 *     low-balance-threshold-paise: 100000   # ₹1,000
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "orders.wallet")
@Validated
public class WalletProperties {

    /**
     * When a wallet debit drops the balance from at/above this to below it, the merchant is alerted
     * once to top up. Default ₹1,000. Set to 0 to disable the alert.
     */
    @PositiveOrZero
    private long lowBalanceThresholdPaise = 100_000;

    public long getLowBalanceThresholdPaise() { return lowBalanceThresholdPaise; }
    public void setLowBalanceThresholdPaise(long v) { this.lowBalanceThresholdPaise = v; }
}
