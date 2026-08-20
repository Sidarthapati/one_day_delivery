package com.oneday.app.prod;

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

    @Test
    void passesWithStrongSecretAndPinnedOrigins() {
        assertThatCode(() -> new ProdEnvGuard(env(STRONG_SECRET, GOOD_ORIGINS)).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCommittedPlaceholderSecret() {
        var guard = new ProdEnvGuard(env("change-me-in-dev-only-must-be-at-least-32-characters-long", GOOD_ORIGINS));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev placeholder");
    }

    @Test
    void rejectsShortSecret() {
        var guard = new ProdEnvGuard(env("tooshort", GOOD_ORIGINS));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than");
    }

    @Test
    void rejectsMissingSecret() {
        var guard = new ProdEnvGuard(env(null, GOOD_ORIGINS));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsWildcardCors() {
        var guard = new ProdEnvGuard(env(STRONG_SECRET, "*"));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void rejectsMissingCors() {
        var guard = new ProdEnvGuard(env(STRONG_SECRET, null));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-origins");
    }
}
