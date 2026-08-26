package com.oneday.orders.service.impl;

import com.oneday.common.port.dto.NotificationEventType;
import com.oneday.common.port.dto.NotificationRequest;
import com.oneday.orders.domain.NotificationChannel;
import com.oneday.orders.domain.NotificationLog;
import com.oneday.orders.domain.NotificationStatus;
import com.oneday.orders.repository.NotificationLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The seam's contract: render the event's template, and enqueue exactly one outbox row per channel
 * the template declares AND the recipient can receive on. These pin the fan-out + the template render.
 */
class NotificationServiceImplTest {

    private final NotificationLogRepository repo = mock(NotificationLogRepository.class);
    private final NotificationServiceImpl service = new NotificationServiceImpl(repo);

    @SuppressWarnings("unchecked")
    private List<NotificationLog> captureSaved() {
        ArgumentCaptor<List<NotificationLog>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(cap.capture());
        return cap.getValue();
    }

    @Test
    void otpGoesToSmsOnly_withRenderedBody() {
        service.send(new NotificationRequest(NotificationEventType.OTP_GENERATED, "a@b.com", "+919000000001",
                Map.of("otp", "123456", "ttl_minutes", "10")));

        List<NotificationLog> rows = captureSaved();
        assertThat(rows).hasSize(1);
        NotificationLog r = rows.get(0);
        assertThat(r.getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(r.getRecipient()).isEqualTo("+919000000001");
        assertThat(r.getBody()).isEqualTo("Your Godspeed pickup OTP is 123456. It expires in 10 minutes.");
        assertThat(r.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(r.getSubject()).isNull();   // SMS carries no subject
    }

    @Test
    void stateChangeFansOutToEmailAndSms() {
        service.send(new NotificationRequest(NotificationEventType.STATE_CHANGED, "a@b.com", "+919000000001",
                Map.of("shipment_ref", "1DD-DEL-1", "status", "Delivered")));

        List<NotificationLog> rows = captureSaved();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(NotificationLog::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.SMS);
        NotificationLog email = rows.stream().filter(r -> r.getChannel() == NotificationChannel.EMAIL).findFirst().orElseThrow();
        assertThat(email.getSubject()).isEqualTo("Update on your shipment 1DD-DEL-1");
        assertThat(email.getBody()).isEqualTo("Your shipment 1DD-DEL-1 is now Delivered.");
    }

    @Test
    void skipsAChannelWhenTheRecipientCantReceiveIt() {
        // STATE_CHANGED wants EMAIL+SMS, but only an email is given → just the email row.
        service.send(new NotificationRequest(NotificationEventType.STATE_CHANGED, "a@b.com", null,
                Map.of("shipment_ref", "1DD-DEL-1", "status", "Delivered")));

        List<NotificationLog> rows = captureSaved();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChannel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void nothingEnqueuedWhenNoDeliverableChannel() {
        // OTP wants SMS, but no phone → nothing to send.
        service.send(new NotificationRequest(NotificationEventType.OTP_GENERATED, "a@b.com", null,
                Map.of("otp", "123456")));
        verify(repo, never()).saveAll(org.mockito.ArgumentMatchers.any());
    }
}
