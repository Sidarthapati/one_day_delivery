package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Notification provider config. Both channels default to {@code log} → no external dependency, the
 * message is written to the app log (dev/staging). Flip {@code notify.sms.provider=msg91} /
 * {@code notify.email.provider=sendgrid} with real credentials (env only, never committed) to send
 * for real. Mirrors {@link PayoutProperties}.
 */
@Component
@ConfigurationProperties(prefix = "notify")
public class NotifyProperties {

    private final Sms sms = new Sms();
    private final Email email = new Email();
    private final WhatsApp whatsapp = new WhatsApp();

    /** How many delivery attempts the outbox drain makes before leaving a row FAILED. */
    private int maxAttempts = 3;

    public Sms getSms() { return sms; }
    public Email getEmail() { return email; }
    public WhatsApp getWhatsapp() { return whatsapp; }
    public int getMaxAttempts() { return maxAttempts; }
    // Clamp to >= 1: a zero/negative cap would make the drain query (attempts < max) exclude every
    // new row, stranding notifications PENDING forever.
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, maxAttempts); }

    public static class Sms {
        /** log | msg91 */
        private String provider = "log";
        private String msg91AuthKey = "";
        private String msg91SenderId = "";
        /** DLT-approved template id (India regulatory requirement). */
        private String msg91TemplateId = "";

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getMsg91AuthKey() { return msg91AuthKey; }
        public void setMsg91AuthKey(String v) { this.msg91AuthKey = v; }
        public String getMsg91SenderId() { return msg91SenderId; }
        public void setMsg91SenderId(String v) { this.msg91SenderId = v; }
        public String getMsg91TemplateId() { return msg91TemplateId; }
        public void setMsg91TemplateId(String v) { this.msg91TemplateId = v; }
    }

    public static class WhatsApp {
        /** log | meta */
        private String provider = "log";
        /** Meta WhatsApp Cloud API phone-number id (the sending number). */
        private String phoneNumberId = "";
        /** Meta system-user / app access token. Env only — never committed. */
        private String accessToken = "";
        /** Token Meta echoes back on the webhook verify (GET) handshake — used by the inbound seam. */
        private String verifyToken = "";
        /** App secret for verifying inbound webhook signatures (X-Hub-Signature-256). */
        private String appSecret = "";

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getPhoneNumberId() { return phoneNumberId; }
        public void setPhoneNumberId(String v) { this.phoneNumberId = v; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { this.accessToken = v; }
        public String getVerifyToken() { return verifyToken; }
        public void setVerifyToken(String v) { this.verifyToken = v; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String v) { this.appSecret = v; }
    }

    public static class Email {
        /** log | sendgrid */
        private String provider = "log";
        private String sendgridApiKey = "";
        private String fromEmail = "no-reply@godspeed.example";
        private String fromName = "Godspeed";

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getSendgridApiKey() { return sendgridApiKey; }
        public void setSendgridApiKey(String v) { this.sendgridApiKey = v; }
        public String getFromEmail() { return fromEmail; }
        public void setFromEmail(String v) { this.fromEmail = v; }
        public String getFromName() { return fromName; }
        public void setFromName(String v) { this.fromName = v; }
    }
}
