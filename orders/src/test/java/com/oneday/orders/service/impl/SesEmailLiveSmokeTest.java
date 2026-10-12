package com.oneday.orders.service.impl;

import com.oneday.orders.config.NotifyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * Live round-trip against real Amazon SES, exercising the production {@link SesEmailSender#send} path.
 * Runs ONLY when {@code SES_SMOKE_EMAIL} is set (plus the SES creds), so CI without credentials skips
 * it — mirrors {@code common.storage.R2LiveSmokeTest}. Sends one real email to {@code SES_SMOKE_EMAIL};
 * verify it arrives in that inbox. In the SES sandbox the recipient must be a verified identity.
 */
@EnabledIfEnvironmentVariable(named = "SES_SMOKE_EMAIL", matches = ".+")
class SesEmailLiveSmokeTest {

    @Test
    void sendsRealEmail() {
        NotifyProperties props = new NotifyProperties();
        NotifyProperties.Email e = props.getEmail();
        e.setSesRegion(getenvOr("NOTIFY_EMAIL_SES_REGION", "ap-south-1"));
        e.setSesAccessKeyId(System.getenv("NOTIFY_EMAIL_SES_ACCESS_KEY_ID"));
        e.setSesSecretAccessKey(System.getenv("NOTIFY_EMAIL_SES_SECRET_ACCESS_KEY"));
        e.setFromEmail(getenvOr("NOTIFY_EMAIL_FROM_EMAIL", "no-reply@godspeed.co.in"));

        try (SesClient ses = SesClient.builder()
                .region(Region.of(e.getSesRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(e.getSesAccessKeyId(), e.getSesSecretAccessKey())))
                .build()) {
            new SesEmailSender(ses, props).send(
                    System.getenv("SES_SMOKE_EMAIL"),
                    "Godspeed SES smoke test",
                    "Hello from Amazon SES via SesEmailSender — " + System.nanoTime());
        }
        // No NotificationDeliveryException thrown => SES accepted the message for delivery.
    }

    private static String getenvOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
