package com.oneday.common.log;

import net.logstash.logback.argument.StructuredArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * One-line audit trail for the important business activities of a shipment/parcel. Every call
 * writes a single structured line to the dedicated {@code com.oneday.audit} logger: the event
 * name plus typed key/value fields. Under the JSON encoder (staging/prod) those fields become
 * top-level JSON keys, so a log platform (Axiom) can query {@code audit_event="shipment.transition"}
 * or {@code shipmentRef="1DD-…"} directly; under the plain console (local dev) they render as
 * {@code key=value} appended to the message.
 *
 * <p>Usage:
 * <pre>{@code
 * AuditLog.event("shipment.transition")
 *         .kv("from", from).kv("to", target)
 *         .kv("shipmentRef", ref).kv("triggeredBy", actor)
 *         .log();
 * }</pre></p>
 *
 * <p><b>PII discipline:</b> log IDs, states, actor/location ids, timestamps only — never customer
 * name / phone / address / OTP.</p>
 */
public final class AuditLog {

    /** Dedicated logger name so audit lines can be routed/filtered independently. */
    private static final Logger log = LoggerFactory.getLogger("com.oneday.audit");

    private AuditLog() {
    }

    /** Begin an audit line for {@code eventName} (e.g. {@code "shipment.booked"}). */
    public static Builder event(String eventName) {
        return new Builder(eventName);
    }

    public static final class Builder {
        private final String eventName;
        private final List<StructuredArgument> args = new ArrayList<>();

        private Builder(String eventName) {
            this.eventName = eventName;
            this.args.add(keyValue("audit_event", eventName));
        }

        /** Add a structured field. Null values are skipped so absent ids don't clutter the line. */
        public Builder kv(String key, Object value) {
            if (value != null) {
                args.add(keyValue(key, value));
            }
            return this;
        }

        /** Emit the audit line at INFO. */
        public void log() {
            log.info(eventName, args.toArray());
        }
    }
}
