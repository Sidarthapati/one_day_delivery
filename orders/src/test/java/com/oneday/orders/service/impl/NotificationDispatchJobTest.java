package com.oneday.orders.service.impl;

import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.domain.NotificationChannel;
import com.oneday.orders.domain.NotificationLog;
import com.oneday.orders.domain.NotificationStatus;
import com.oneday.orders.repository.NotificationLogRepository;
import com.oneday.orders.service.EmailSender;
import com.oneday.orders.service.NotificationDeliveryException;
import com.oneday.orders.service.SmsSender;
import com.oneday.orders.service.WhatsAppSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drain must mark a delivered row SENT and a thrown-on row FAILED, always bumping attempts so the
 * retry cap eventually stops it. These pin both outcomes.
 */
class NotificationDispatchJobTest {

    private final NotificationLogRepository repo = mock(NotificationLogRepository.class);
    private final EmailSender email = mock(EmailSender.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final WhatsAppSender whatsApp = mock(WhatsAppSender.class);
    private final NotifyProperties props = new NotifyProperties();
    private final NotificationDispatchJob job = new NotificationDispatchJob(repo, email, sms, whatsApp, props);

    private static NotificationLog emailRow() {
        NotificationLog r = new NotificationLog();
        r.setEventType("STATE_CHANGED");
        r.setChannel(NotificationChannel.EMAIL);
        r.setRecipient("a@b.com");
        r.setSubject("Hi");
        r.setBody("Body");
        r.setStatus(NotificationStatus.PENDING);
        return r;
    }

    @Test
    void deliveredRowIsMarkedSent() {
        NotificationLog row = emailRow();
        when(repo.findTop200ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(any(), anyInt()))
                .thenReturn(List.of(row));

        job.drain();

        verify(email).send("a@b.com", "Hi", "Body");
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getSentAt()).isNotNull();
        verify(repo).save(row);
    }

    @Test
    void whatsAppRowIsDeliveredViaTheWhatsAppSender() {
        NotificationLog row = new NotificationLog();
        row.setEventType("SHIPMENT_DELAYED");
        row.setChannel(NotificationChannel.WHATSAPP);
        row.setRecipient("+919000000009");
        row.setBody("Your delivery is running late");
        row.setStatus(NotificationStatus.PENDING);
        when(repo.findTop200ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(any(), anyInt()))
                .thenReturn(List.of(row));

        job.drain();

        verify(whatsApp).send("+919000000009", "Your delivery is running late");
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void failedSendIsMarkedFailedWithErrorAndBumpedAttempts() {
        NotificationLog row = emailRow();
        row.setAttempts(1);   // a prior failed attempt
        when(repo.findTop200ByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(any(), anyInt()))
                .thenReturn(List.of(row));
        doThrow(new NotificationDeliveryException("sendgrid 500")).when(email).send(any(), any(), any());

        job.drain();

        assertThat(row.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getError()).contains("sendgrid 500");
        assertThat(row.getSentAt()).isNull();
        verify(repo).save(row);
    }
}
