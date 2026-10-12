package com.oneday.orders.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * Builds the SES client only when {@code notify.email.provider=ses}. Kept out of the sender so the
 * sender can be unit-tested with a mock client. Uses explicit static creds when configured, else the
 * default AWS chain (env/instance role). Mirrors the R2/{@code storage} credential pattern.
 */
@Configuration
@ConditionalOnProperty(name = "notify.email.provider", havingValue = "ses")
class SesClientConfig {

    @Bean(destroyMethod = "close")
    SesClient sesClient(NotifyProperties props) {
        NotifyProperties.Email cfg = props.getEmail();
        var builder = SesClient.builder().region(Region.of(cfg.getSesRegion()));
        if (!cfg.getSesAccessKeyId().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.getSesAccessKeyId(), cfg.getSesSecretAccessKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }
}
