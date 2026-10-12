package com.oneday.orders.service.impl;

import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.service.NotificationDeliveryException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the SES request mapping and the best-effort contract the outbox drain relies on: a delivered
 * message maps From/To/Subject/Body correctly; a client failure surfaces as a
 * {@link NotificationDeliveryException} so the row is marked FAILED and retried.
 */
class SesEmailSenderTest {

    private final SesClient ses = mock(SesClient.class);
    private final NotifyProperties props = new NotifyProperties();
    private final SesEmailSender sender = new SesEmailSender(ses, props);

    @Test
    void mapsRequestAndSends() {
        sender.send("rcpt@example.com", "Hello", "Body text");

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        SendEmailRequest req = captor.getValue();
        assertThat(req.source()).isEqualTo("Godspeed <no-reply@godspeed.co.in>");
        assertThat(req.destination().toAddresses()).containsExactly("rcpt@example.com");
        assertThat(req.message().subject().data()).isEqualTo("Hello");
        assertThat(req.message().subject().charset()).isEqualTo("UTF-8");
        assertThat(req.message().body().text().data()).isEqualTo("Body text");
        assertThat(req.message().body().text().charset()).isEqualTo("UTF-8");
    }

    @Test
    void wrapsClientFailureSoTheOutboxRetries() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesException.builder().message("throttled").build());

        assertThatThrownBy(() -> sender.send("rcpt@example.com", "s", "b"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("rcpt@example.com");
    }
}
