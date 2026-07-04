package com.oneday.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo-only ({@code @Profile("!prod")}) in-memory observation of the real RabbitMQ traffic. Two hooks
 * write here (see {@code AmqpTapConfig}): a before-publish processor on the {@link
 * org.springframework.amqp.rabbit.core.RabbitTemplate} records every actual {@code send()}, and an
 * after-receive processor on the listener container factory records every actual {@code onMessage()}.
 * These are genuine bus events (not narration) — the same JVM is both producer and consumer in this
 * monolith, so this is the app's true view of what was published and consumed.
 *
 * <p>The Execution tab polls {@code GET /api/demo/amqp-tap} during a run and renders each entry as a
 * {@code PUBLISH} / {@code CONSUME} line in the live RabbitMQ feed. A bounded ring keeps memory flat.</p>
 */
@Component
@Profile("!prod")
public class AmqpTap {

    /** One observed bus event. {@code dir} is {@code "PUBLISH"} or {@code "CONSUME"};
     *  {@code detail} is a short human hint pulled from the payload (ref, state, event type). */
    public record Entry(long seq, String dir, String exchange, String routingKey,
                        String type, String queue, String detail, long ts) {}

    private static final int MAX = 600;

    private final AtomicLong seq = new AtomicLong();
    private final Deque<Entry> ring = new ArrayDeque<>();

    /** Record a real publish (template send funnel). */
    public synchronized void publish(String exchange, String routingKey, String type, String detail) {
        add(new Entry(seq.incrementAndGet(), "PUBLISH",
                blankToDash(exchange), routingKey, type, null, detail, System.currentTimeMillis()));
    }

    /** Record a real consume (listener after-receive). */
    public synchronized void consume(String queue, String type, String detail) {
        add(new Entry(seq.incrementAndGet(), "CONSUME",
                null, null, type, blankToDash(queue), detail, System.currentTimeMillis()));
    }

    private void add(Entry e) {
        ring.addLast(e);
        while (ring.size() > MAX) ring.removeFirst();
    }

    /** Entries with {@code seq > after}, oldest first. {@code after} of the current head syncs "now". */
    public synchronized List<Entry> since(long after) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ring) if (e.seq() > after) out.add(e);
        return out;
    }

    /** Newest sequence number issued so far (so a client can fast-forward its cursor to "now"). */
    public long head() {
        return seq.get();
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "(default)" : s;
    }
}
