package com.oneday.app.demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo-only in-memory event feed (the "RabbitMQ feed ▸ …" panel in the journey UI). A bounded ring
 * buffer with a monotonic sequence so the UI can long-poll {@code /api/demo/journey/run-events?after=<seq>}
 * and append only what's new. Never used in prod (the whole demo package is {@code @Profile("!prod")}).
 *
 * <p>Ported from {@code routing.demo.DemoLog} (the M6 execution demo); kept package-local so the M7
 * journey driver has no dependency on the M6 demo package.</p>
 */
final class DemoLog {

    /** One line in the feed. {@code kind} drives the colour/icon in the UI. */
    record Entry(long seq, Instant at, String kind, String message) {}

    private static final int MAX = 600;

    private final AtomicLong seq = new AtomicLong(0);
    private final ArrayList<Entry> entries = new ArrayList<>();

    synchronized void add(String kind, String message) {
        entries.add(new Entry(seq.incrementAndGet(), Instant.now(), kind, message));
        if (entries.size() > MAX) {
            entries.subList(0, entries.size() - MAX).clear();
        }
    }

    synchronized void clear() {
        entries.clear();
    }

    /** Entries with seq strictly greater than {@code after} (0 = everything still buffered). */
    synchronized List<Entry> since(long after) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > after) out.add(e);
        }
        return out;
    }

    long lastSeq() {
        return seq.get();
    }
}
