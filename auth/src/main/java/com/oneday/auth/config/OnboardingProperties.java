package com.oneday.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Business-onboarding policy. Most self-signups are small merchants: when KYC is clean they are
 * <b>auto-approved instantly</b> (created + account provisioned) for a one-shot onboarding. Only
 * the exceptions land in the ADMIN review queue — KYC that didn't verify, or a business type
 * explicitly flagged for review (e.g. regulated categories, or large/credit onboardings later).
 */
@Component
@ConfigurationProperties(prefix = "onboarding")
public class OnboardingProperties {

    private AutoApprove autoApprove = new AutoApprove();

    public AutoApprove getAutoApprove() { return autoApprove; }
    public void setAutoApprove(AutoApprove v) { this.autoApprove = v; }

    public static class AutoApprove {
        /** Master switch. When false, every business onboarding goes to the ADMIN queue. */
        private boolean enabled = true;

        /** Business types that always require manual review even with clean KYC (empty = none). */
        private Set<String> reviewBusinessTypes = Set.of();

        /**
         * Requested credit above this (paise) forces manual review. Self-signup is prepaid
         * (credit 0) today, so this only bites once the signup collects a requested credit line.
         */
        private long maxCreditPaise = 0L;

        /**
         * A self-declared monthly volume at/above this forces manual review (large merchant). The
         * "2000+" band has a floor of 2000, so the default sends exactly that band to the queue.
         */
        private int maxMonthlyOrders = 2000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        public Set<String> getReviewBusinessTypes() { return reviewBusinessTypes; }
        public void setReviewBusinessTypes(Set<String> v) { this.reviewBusinessTypes = v; }

        public long getMaxCreditPaise() { return maxCreditPaise; }
        public void setMaxCreditPaise(long v) { this.maxCreditPaise = v; }

        public int getMaxMonthlyOrders() { return maxMonthlyOrders; }
        public void setMaxMonthlyOrders(int v) { this.maxMonthlyOrders = v; }
    }
}
