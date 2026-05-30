package com.oneday.orders.service;

import com.oneday.orders.domain.enums.TriggerSource;

import java.time.Instant;

/**
 * Carries metadata about why a state transition is happening.
 * Passed into the state machine and written verbatim into {@code ShipmentStateHistory}.
 *
 * <p>Use the factory methods rather than the constructor directly:</p>
 * <pre>
 *   TransitionContext.fromApi(userId, requestId)
 *   TransitionContext.fromKafka("m5-consumer", kafkaMessageKey)
 *   TransitionContext.fromSystem("idempotency-purge-job")
 * </pre>
 */
public final class TransitionContext {

    private final String triggeredBy;
    private final TriggerSource triggerSource;
    private final String eventRef;   // nullable — Kafka message key or API request ID
    private final String notes;      // nullable — human-readable context
    private final Instant occurredAt;

    private TransitionContext(String triggeredBy,
                              TriggerSource triggerSource,
                              String eventRef,
                              String notes,
                              Instant occurredAt) {
        this.triggeredBy = triggeredBy;
        this.triggerSource = triggerSource;
        this.eventRef = eventRef;
        this.notes = notes;
        this.occurredAt = occurredAt;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /** Transition triggered by a customer or operator via the REST API. */
    public static TransitionContext fromApi(String userId, String requestId) {
        return new TransitionContext(userId, TriggerSource.API, requestId, null, Instant.now());
    }

    /**
     * Transition triggered by a Kafka event from another module.
     * {@code eventRef} should be the Kafka message key for correlation back to the topic log.
     */
    public static TransitionContext fromKafka(String consumerIdentifier, String kafkaMessageKey) {
        return new TransitionContext(consumerIdentifier, TriggerSource.KAFKA_EVENT, kafkaMessageKey, null, Instant.now());
    }

    /** Transition triggered by an internal scheduled job or system process. */
    public static TransitionContext fromSystem(String systemIdentifier) {
        return new TransitionContext(systemIdentifier, TriggerSource.SYSTEM, null, null, Instant.now());
    }

    /** Builder-style notes attachment — returns a new context with the note added. */
    public TransitionContext withNotes(String notes) {
        return new TransitionContext(triggeredBy, triggerSource, eventRef, notes, occurredAt);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getTriggeredBy() { return triggeredBy; }
    public TriggerSource getTriggerSource() { return triggerSource; }
    public String getEventRef() { return eventRef; }
    public String getNotes() { return notes; }
    public Instant getOccurredAt() { return occurredAt; }
}
