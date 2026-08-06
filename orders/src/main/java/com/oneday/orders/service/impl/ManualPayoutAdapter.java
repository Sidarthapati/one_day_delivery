package com.oneday.orders.service.impl;

import com.oneday.orders.config.PayoutProperties;
import com.oneday.orders.domain.BankVerificationState;
import com.oneday.orders.service.PayoutPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Default payout adapter — no external provider. Verification does structural validation only
 * (IFSC format + account-number sanity) and marks the account MANUAL_VERIFIED for a finance
 * eyeball; there is no ₹1 penny-drop. Payout is a manual bank transfer: this returns "not settled"
 * and the admin records the real UTR in the console. Swap to RazorpayX (payout.provider=razorpayx)
 * for real penny-drop verification and API payouts.
 */
@Component
@ConditionalOnProperty(name = "payout.provider", havingValue = "manual", matchIfMissing = true)
class ManualPayoutAdapter implements PayoutPort {

    private static final Logger log = LoggerFactory.getLogger(ManualPayoutAdapter.class);
    // IFSC: 4 bank letters, a 0, then 6 branch alphanumerics (RBI format).
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    ManualPayoutAdapter(PayoutProperties props) {
        log.info("[payout] provider=manual — bank accounts are finance-verified; payouts are manual (UTR entered by admin)");
    }

    @Override
    public VerificationOutcome verifyBankAccount(BankAccount account, String beneficiaryLegalName) {
        if (account.ifsc() == null || !IFSC.matcher(account.ifsc().toUpperCase()).matches()) {
            return new VerificationOutcome(BankVerificationState.FAILED, null, "IFSC format is invalid");
        }
        String acct = account.accountNumber() == null ? "" : account.accountNumber().replaceAll("\\s", "");
        if (!acct.matches("\\d{9,18}")) {
            return new VerificationOutcome(BankVerificationState.FAILED, null, "Account number must be 9–18 digits");
        }
        // No penny-drop available → flag for a finance eyeball, don't claim a bank-confirmed match.
        return new VerificationOutcome(BankVerificationState.MANUAL_VERIFIED, null,
                "Accepted for manual verification (no penny-drop provider configured)");
    }

    @Override
    public PayoutResult createPayout(PayoutRequest request) {
        // Manual mode: money is moved by hand from the company bank; the admin enters the UTR.
        log.info("[payout:manual] remittance {} ({} paise) queued for manual bank transfer",
                request.remittanceRef(), request.amountPaise());
        return new PayoutResult(false, null, null, "Make the bank transfer manually and record the UTR");
    }
}
