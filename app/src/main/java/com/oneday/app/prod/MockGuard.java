package com.oneday.app.prod;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Belt-and-suspenders boot guard: mock/dev beans are all {@code @Profile("!prod")} so they should
 * never register under prod. If one slips through (e.g. a dropped profile annotation), refuse to
 * start — a mock payment/OTP/seeder bean must never run in production.
 *
 * <p>Only active under the {@code prod} profile, so staging/dev boot is untouched.
 */
@Component
@Profile("prod")
public class MockGuard implements InitializingBean {

    // Default bean names (decapitalised simple class name) of every @Profile("!prod") bean.
    private static final String[] MOCK_BEAN_NAMES = {
            "mockPaymentController",
            "mockWalletController",
            "devOtpController",
            "b2cSelfDropDemoController",
            "gridSeeder",
            "stubEtaAdapter",
            "consolidatorLegRollForwardJob",
    };

    private final ApplicationContext ctx;

    public MockGuard(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> leaked = new ArrayList<>();
        for (String name : MOCK_BEAN_NAMES) {
            if (ctx.containsBean(name)) {
                leaked.add(name);
            }
        }
        if (!leaked.isEmpty()) {
            throw new IllegalStateException(
                    "PROD BOOT REFUSED — mock/dev bean(s) active under the prod profile: " + leaked
                            + ". These must never run in production; check their @Profile(\"!prod\").");
        }
    }
}
