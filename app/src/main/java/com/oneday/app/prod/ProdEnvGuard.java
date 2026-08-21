package com.oneday.app.prod;

import com.oneday.orders.config.RazorpayProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast validation of prod configuration at boot. Only active under the {@code prod} profile,
 * so staging/dev is untouched. Aggregates every problem into one message so a misconfigured deploy
 * surfaces all gaps at once rather than one-by-one.
 *
 * <p>Datasource/broker URLs already fail fast via {@code application-prod.yml} (no localhost
 * default); the unique value here is rejecting a weak/placeholder JWT secret and an unset/wildcard
 * CORS origin list — silent-but-insecure misconfigurations a missing-placeholder check won't catch.
 */
@Component
@Profile("prod")
public class ProdEnvGuard implements InitializingBean {

    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private final Environment env;
    private final RazorpayProperties razorpay;

    public ProdEnvGuard(Environment env, RazorpayProperties razorpay) {
        this.env = env;
        this.razorpay = razorpay;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        String jwt = env.getProperty("jwt.secret");
        if (isBlank(jwt)) {
            problems.add("jwt.secret (JWT_SECRET) is not set");
        } else {
            if (jwt.length() < MIN_JWT_SECRET_LENGTH) {
                problems.add("jwt.secret is shorter than " + MIN_JWT_SECRET_LENGTH + " characters");
            }
            if (jwt.contains("change-me")) {
                problems.add("jwt.secret is still the committed dev placeholder — set a real JWT_SECRET");
            }
        }

        String cors = env.getProperty("godspeed.cors.allowed-origins");
        if (isBlank(cors)) {
            problems.add("godspeed.cors.allowed-origins (GODSPEED_CORS_ALLOWED_ORIGINS) is not set");
        } else if (cors.trim().equals("*")) {
            problems.add("godspeed.cors.allowed-origins is a wildcard '*' — pin the real console origins in prod");
        }

        // When Razorpay is live, the HMAC secret/key must be real — never the committed mock/demo
        // default (which would sign with a publicly-known secret, making signatures forgeable).
        if (razorpay.isLive()) {
            String secret = razorpay.getKeySecret();
            if (isBlank(secret) || secret.contains("mock") || secret.contains("change_me") || secret.contains("demo")) {
                problems.add("razorpay.key-secret is unset or still the committed mock/demo default while "
                        + "razorpay.live=true — set a real RAZORPAY_KEY_SECRET");
            }
            String keyId = razorpay.getKeyId();
            if (isBlank(keyId) || keyId.startsWith("rzp_test") || keyId.contains("demo")) {
                problems.add("razorpay.key-id is unset or a test/demo key while razorpay.live=true — "
                        + "set a real RAZORPAY_KEY_ID");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "PROD BOOT REFUSED — invalid production configuration:\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
