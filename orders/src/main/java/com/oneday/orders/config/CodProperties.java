package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * COD remittance policy. The fee we retain per payout is {@code flat + percent% of gross}. Stored
 * on each remittance so the historical fee is auditable even if config later changes.
 */
@Component
@ConfigurationProperties(prefix = "cod")
public class CodProperties {

    /** Percent of the gross collected retained as the COD handling fee. */
    private double remittanceFeePercent = 1.5;

    /** Flat fee (paise) added on top of the percentage. */
    private long remittanceFeeFlatPaise = 0L;

    public double getRemittanceFeePercent() { return remittanceFeePercent; }
    public void setRemittanceFeePercent(double v) { this.remittanceFeePercent = v; }

    public long getRemittanceFeeFlatPaise() { return remittanceFeeFlatPaise; }
    public void setRemittanceFeeFlatPaise(long v) { this.remittanceFeeFlatPaise = v; }

    /** Fee retained on a gross payout (rounded to the nearest paise), never more than the gross. */
    public long feeOn(long grossPaise) {
        long fee = remittanceFeeFlatPaise + Math.round(grossPaise * remittanceFeePercent / 100.0);
        return Math.min(fee, grossPaise);
    }
}
