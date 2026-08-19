package com.oneday.orders.service.impl;

import com.oneday.orders.config.PayoutProperties;
import com.oneday.orders.domain.BankVerificationState;
import com.oneday.orders.service.PayoutPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Real payouts via <b>RazorpayX</b> (activated with {@code payout.provider=razorpayx}). This is the
 * concrete "how we connect to the bank": the object chain is
 * Contact → Fund Account (account_number + IFSC) → Fund Account Validation (the ₹1 penny-drop) →
 * Payout (IMPS/NEFT/UPI). Auth is HTTP Basic with the RazorpayX key id/secret (env only).
 *
 * <p>Verification is asynchronous at RazorpayX: creating a validation returns immediately and the
 * bank's registered name + result arrive on the {@code fund_account.validation.completed} webhook —
 * so {@link #verifyBankAccount} returns PENDING with the fund-account id, and the webhook handler
 * (a follow-up) flips it to VERIFIED/FAILED. Payout is created synchronously and settles on the
 * {@code payout.processed} webhook, which carries the bank UTR.
 *
 * @see <a href="https://razorpay.com/docs/x/apis/">RazorpayX API</a>
 */
@Component
@ConditionalOnProperty(name = "payout.provider", havingValue = "razorpayx")
class RazorpayXPayoutAdapter implements PayoutPort {

    private static final Logger log = LoggerFactory.getLogger(RazorpayXPayoutAdapter.class);
    private static final String BASE = "https://api.razorpay.com/v1";

    private final PayoutProperties props;
    private final RestClient http;

    RazorpayXPayoutAdapter(PayoutProperties props) {
        this.props = props;
        String basic = Base64.getEncoder().encodeToString(
                (props.getRazorpayxKeyId() + ":" + props.getRazorpayxKeySecret()).getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(java.time.Duration.ofSeconds(3))
                                .withReadTimeout(java.time.Duration.ofSeconds(15))))
                .baseUrl(BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
        log.info("[payout] provider=razorpayx — real penny-drop verification + API payouts (mode {})", props.getMode());
    }

    @Override
    public VerificationOutcome verifyBankAccount(BankAccount account, String beneficiaryLegalName) {
        try {
            // 1) Contact — the merchant we're paying.
            String contactId = post("/contacts", Map.of(
                    "name", beneficiaryLegalName,
                    "type", "vendor")).get("id").toString();

            // 2) Fund account — their bank account, referenced by the contact.
            String fundAccountId = post("/fund_accounts", Map.of(
                    "contact_id", contactId,
                    "account_type", "bank_account",
                    "bank_account", Map.of(
                            "name", beneficiaryLegalName,
                            "ifsc", account.ifsc(),
                            "account_number", account.accountNumber()))).get("id").toString();

            // 3) Fund-account validation — the ₹1 penny-drop. Result (registered name + active/invalid)
            //    arrives on the fund_account.validation.completed webhook, which finalises the state.
            post("/fund_accounts/validations", Map.of(
                    "account_number", props.getRazorpayxAccountNumber(),
                    "fund_account", Map.of("id", fundAccountId),
                    "amount", 100,
                    "currency", "INR"));

            log.info("[payout:razorpayx] penny-drop started for fund account {}", fundAccountId);
            return new VerificationOutcome(BankVerificationState.PENDING, fundAccountId,
                    "Penny-drop initiated — verification completes on the provider webhook");
        } catch (RestClientResponseException e) {
            log.warn("[payout:razorpayx] verification failed: {}", e.getResponseBodyAsString());
            return new VerificationOutcome(BankVerificationState.FAILED, null,
                    "Provider rejected the bank details");
        }
    }

    @Override
    public PayoutResult createPayout(PayoutRequest request) {
        if (request.fundAccountRef() == null || request.fundAccountRef().isBlank()) {
            throw new IllegalStateException("No verified fund account for remittance " + request.remittanceRef());
        }
        try {
            Map<String, Object> body = post("/payouts", Map.of(
                    "account_number", props.getRazorpayxAccountNumber(),
                    "fund_account_id", request.fundAccountRef(),
                    "amount", request.amountPaise(),
                    "currency", "INR",
                    "mode", props.getMode(),
                    "purpose", "payout",
                    "queue_if_low_balance", true,
                    "reference_id", request.remittanceRef()));
            String payoutId = String.valueOf(body.get("id"));
            String status = String.valueOf(body.get("status"));   // queued|processing|processed
            String utr = body.get("utr") == null ? null : body.get("utr").toString();
            boolean settled = "processed".equals(status);
            log.info("[payout:razorpayx] payout {} for remittance {} → status {}", payoutId, request.remittanceRef(), status);
            return new PayoutResult(settled, utr, payoutId, "Payout " + status);
        } catch (RestClientResponseException e) {
            log.warn("[payout:razorpayx] payout failed: {}", e.getResponseBodyAsString());
            throw new IllegalStateException("RazorpayX payout failed: " + e.getStatusCode());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        return http.post().uri(path).body(body).retrieve().body(Map.class);
    }
}
