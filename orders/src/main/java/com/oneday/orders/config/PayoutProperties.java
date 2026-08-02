package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Payout provider config. Default {@code provider=manual} → no external dependency: bank accounts
 * are finance-verified and payouts are manual bank transfers with the UTR typed in. Set
 * {@code payout.provider=razorpayx} with real credentials (env only, never committed) to do real
 * penny-drop verification + API payouts via RazorpayX.
 */
@Component
@ConfigurationProperties(prefix = "payout")
public class PayoutProperties {

    /** manual | razorpayx */
    private String provider = "manual";

    /** RazorpayX key id (basic-auth user). Passed via env, never committed. */
    private String razorpayxKeyId = "";

    /** RazorpayX key secret (basic-auth password). Passed via env, never committed. */
    private String razorpayxKeySecret = "";

    /** Our RazorpayX account number (source of payouts / penny-drops). */
    private String razorpayxAccountNumber = "";

    /** Payout rail: IMPS | NEFT | UPI. IMPS is instant + works up to ₹5L. */
    private String mode = "IMPS";

    public boolean isRazorpayx() { return "razorpayx".equalsIgnoreCase(provider); }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getRazorpayxKeyId() { return razorpayxKeyId; }
    public void setRazorpayxKeyId(String v) { this.razorpayxKeyId = v; }

    public String getRazorpayxKeySecret() { return razorpayxKeySecret; }
    public void setRazorpayxKeySecret(String v) { this.razorpayxKeySecret = v; }

    public String getRazorpayxAccountNumber() { return razorpayxAccountNumber; }
    public void setRazorpayxAccountNumber(String v) { this.razorpayxAccountNumber = v; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
