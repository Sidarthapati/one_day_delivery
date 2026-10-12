package com.oneday.orders.service.impl;

import com.oneday.orders.config.NotifyProperties;
import com.oneday.orders.service.EmailSender;
import com.oneday.orders.service.NotificationDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

/**
 * Amazon SES email adapter. Gated by {@code notify.email.provider=ses} — off by default. Credentials
 * and region come from {@link NotifyProperties} via {@link SesClientConfig} (env only). A failure
 * throws {@link NotificationDeliveryException} so the outbox drain marks the row FAILED and retries —
 * same contract as the SendGrid adapter.
 */
@Component
@ConditionalOnProperty(name = "notify.email.provider", havingValue = "ses")
class SesEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

    private final SesClient ses;
    private final NotifyProperties props;

    SesEmailSender(SesClient ses, NotifyProperties props) {
        this.ses = ses;
        this.props = props;
        log.info("[notify] email provider=ses (region={}, from={})",
                props.getEmail().getSesRegion(), props.getEmail().getFromEmail());
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        NotifyProperties.Email cfg = props.getEmail();
        String source = cfg.getFromName() + " <" + cfg.getFromEmail() + ">";
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .source(source)
                    .destination(Destination.builder().toAddresses(toEmail).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .text(Content.builder().data(body).charset("UTF-8").build())
                                    .build())
                            .build())
                    .build();
            ses.sendEmail(request);
        } catch (Exception e) {
            throw new NotificationDeliveryException("ses send to " + toEmail + " failed: " + e, e);
        }
    }
}
