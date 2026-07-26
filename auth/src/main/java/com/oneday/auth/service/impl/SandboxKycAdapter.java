package com.oneday.auth.service.impl;

import com.oneday.auth.config.KycProperties;
import com.oneday.common.port.KycPort;
import com.oneday.common.port.dto.kyc.BankAccountResult;
import com.oneday.common.port.dto.kyc.GstinResult;
import com.oneday.common.port.dto.kyc.PanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The single {@link KycPort} implementation (house style, mirroring RazorpayPaymentAdapter):
 * a deterministic <b>mock</b> when {@code kyc.live=false} (default — no external calls), and real
 * Sandbox.co.in calls when {@code kyc.live=true}.
 *
 * <p><b>Mock rules</b> (deterministic, for dev/tests): well-formed GSTIN/PAN verify; anything
 * containing {@code "FAIL"} is rejected (drives the MANUAL_REVIEW path); a bank account of all
 * zeros fails. <b>Live path</b>: authenticate → bearer token → verify; every failure is caught and
 * returned as a {@code failed(...)} result so onboarding never throws on a provider blip.</p>
 *
 * <p><b>TODO(live):</b> the exact Sandbox endpoint paths/response fields below must be confirmed
 * against the provider's API docs before flipping {@code kyc.live=true}. Guarded off by default.</p>
 */
@Component
class SandboxKycAdapter implements KycPort {

    private static final Logger log = LoggerFactory.getLogger(SandboxKycAdapter.class);

    private static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]");
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");

    private final KycProperties props;
    private final RestClient http;

    SandboxKycAdapter(KycProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public GstinResult verifyGstin(String gstin) {
        String g = norm(gstin);
        if (!props.isLive()) {
            if (g == null || !GSTIN.matcher(g).matches() || g.contains("FAIL")) {
                return GstinResult.failed(g, "GSTIN not verified (mock)");
            }
            return new GstinResult(true, g, "Mock Traders Private Limited", "Mock Traders",
                    "Active", "Mock address, India", "Verified (mock)");
        }
        try {
            var body = authed().get()
                    .uri("/gst/compliance/public/gstin/{gstin}", g)
                    .retrieve().body(Map.class);
            return mapGstin(g, body);
        } catch (Exception e) {
            log.warn("Sandbox GSTIN verify failed for {}: {}", g, e.toString());
            return GstinResult.failed(g, "Verification unavailable");
        }
    }

    @Override
    public PanResult verifyPan(String pan, String name) {
        String p = norm(pan);
        if (!props.isLive()) {
            if (p == null || !PAN.matcher(p).matches() || p.contains("FAIL")) {
                return PanResult.failed(p, "PAN not verified (mock)");
            }
            return new PanResult(true, p, name, true, "Verified (mock)");
        }
        try {
            var body = authed().post()
                    .uri("/kyc/pan/verify")
                    .body(Map.of("pan", p, "name_as_per_pan", name == null ? "" : name))
                    .retrieve().body(Map.class);
            return mapPan(p, name, body);
        } catch (Exception e) {
            log.warn("Sandbox PAN verify failed for {}: {}", p, e.toString());
            return PanResult.failed(p, "Verification unavailable");
        }
    }

    @Override
    public BankAccountResult verifyBankAccount(String accountNumber, String ifsc, String beneficiaryName) {
        String acc = norm(accountNumber);
        if (!props.isLive()) {
            if (acc == null || acc.chars().allMatch(c -> c == '0')) {
                return BankAccountResult.failed("Bank account not verified (mock)");
            }
            return new BankAccountResult(true, beneficiaryName, true, "Verified (mock)");
        }
        try {
            var body = authed().post()
                    .uri("/bank/verify")
                    .body(Map.of("account_number", acc, "ifsc", norm(ifsc), "name", beneficiaryName == null ? "" : beneficiaryName))
                    .retrieve().body(Map.class);
            return mapBank(beneficiaryName, body);
        } catch (Exception e) {
            log.warn("Sandbox bank verify failed: {}", e.toString());
            return BankAccountResult.failed("Verification unavailable");
        }
    }

    // ── live helpers ─────────────────────────────────────────────────────────
    // Authenticate then attach the bearer token + api key. Sandbox issues a short-lived
    // access token from /authenticate; we fetch per call (simple; add caching later).

    private RestClient authed() {
        Map<?, ?> auth = http.post()
                .uri("/authenticate")
                .header("x-api-key", props.getApiKey())
                .header("x-api-secret", props.getApiSecret())
                .header("x-api-version", props.getApiVersion())
                .retrieve()
                .body(Map.class);
        String token = auth == null ? null : str(auth.get("access_token"));
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("x-api-key", props.getApiKey())
                .defaultHeader("x-api-version", props.getApiVersion())
                .build();
    }

    @SuppressWarnings("unchecked")
    private GstinResult mapGstin(String gstin, Map<?, ?> body) {
        if (body == null) return GstinResult.failed(gstin, "Empty response");
        Object d = body.get("data");
        Map<String, Object> data = (Map<String, Object>) (d instanceof Map ? d : body);
        String status = str(data.get("status"));
        boolean ok = status != null && status.equalsIgnoreCase("Active");
        return new GstinResult(ok, gstin, str(data.get("legal_name")), str(data.get("trade_name")),
                status, str(data.get("address")), ok ? "Verified" : "GSTIN not active");
    }

    @SuppressWarnings("unchecked")
    private PanResult mapPan(String pan, String name, Map<?, ?> body) {
        if (body == null) return PanResult.failed(pan, "Empty response");
        Object d = body.get("data");
        Map<String, Object> data = (Map<String, Object>) (d instanceof Map ? d : body);
        boolean valid = "valid".equalsIgnoreCase(str(data.get("status")));
        boolean nameMatch = Boolean.TRUE.equals(data.get("name_match")) || valid;
        return new PanResult(valid, pan, str(data.getOrDefault("name", name)), nameMatch,
                valid ? "Verified" : "PAN invalid");
    }

    @SuppressWarnings("unchecked")
    private BankAccountResult mapBank(String name, Map<?, ?> body) {
        if (body == null) return BankAccountResult.failed("Empty response");
        Object d = body.get("data");
        Map<String, Object> data = (Map<String, Object>) (d instanceof Map ? d : body);
        boolean ok = "success".equalsIgnoreCase(str(data.get("status")));
        boolean nameMatch = Boolean.TRUE.equals(data.get("name_match"));
        return new BankAccountResult(ok, str(data.getOrDefault("name_at_bank", name)), nameMatch,
                ok ? "Verified" : "Account not verified");
    }

    private static String norm(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
