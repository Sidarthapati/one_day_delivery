package com.oneday.app.prod;

import com.oneday.orders.config.RazorpayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdEnvGuardTest {

    private static final String STRONG_SECRET = "a-strong-production-secret-value-32ch!!";
    private static final String GOOD_ORIGINS = "https://godspeed-admin.vercel.app";

    private MockEnvironment env(String jwt, String cors) {
        MockEnvironment e = new MockEnvironment();
        if (jwt != null) e.setProperty("jwt.secret", jwt);
        if (cors != null) e.setProperty("godspeed.cors.allowed-origins", cors);
        return e;
    }

    // Default RazorpayProperties has live=false, so the Razorpay checks are skipped — these existing
    // JWT/CORS tests are unaffected. Razorpay-live coverage is added at the bottom.
    private ProdEnvGuard newGuard(MockEnvironment e) {
        return new ProdEnvGuard(e, new RazorpayProperties());
    }

    @Test
    void passesWithStrongSecretAndPinnedOrigins() {
        assertThatCode(() -> newGuard(env(STRONG_SECRET, GOOD_ORIGINS)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCommittedPlaceholderSecret() {
        var guard = newGuard(env("change-me-in-dev-only-must-be-at-least-32-characters-long", GOOD_ORIGINS));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev placeholder");
    }

    @Test
    void rejectsShortSecret() {
        var guard = newGuard(env("tooshort", GOOD_ORIGINS));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than");
    }

    @Test
    void rejectsMissingSecret() {
        var guard = newGuard(env(null, GOOD_ORIGINS));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsWildcardCors() {
        var guard = newGuard(env(STRONG_SECRET, "*"));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void rejectsMissingCors() {
        var guard = newGuard(env(STRONG_SECRET, null));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-origins");
    }

    @Test
    void rejectsLiveRazorpayStillOnMockSecret() {
        RazorpayProperties rz = new RazorpayProperties();
        rz.setLive(true); // keySecret/keyId keep the committed mock/test defaults
        var guard = new ProdEnvGuard(env(STRONG_SECRET, GOOD_ORIGINS), rz);
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("razorpay.key-secret");
    }

    @Test
    void passesWithLiveRazorpayAndRealCredentials() {
        RazorpayProperties rz = new RazorpayProperties();
        rz.setLive(true);
        rz.setKeySecret("a-real-live-razorpay-secret-value");
        rz.setKeyId("rzp_live_realkey123");
        var guard = new ProdEnvGuard(env(STRONG_SECRET, GOOD_ORIGINS), rz);
        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }
}
