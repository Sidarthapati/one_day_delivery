package com.oneday.dispatch.service;

/**
 * Operational re-drive of a dead-lettered stream: drains {@code <exchange>.dlq} and republishes each
 * message to its source exchange with its original routing key, so messages that failed (e.g. a
 * transient outage) can be reprocessed after the cause is fixed.
 */
public interface DlqReplayService {

    /**
     * Replay up to the configured batch limit from {@code exchange}'s DLQ back to {@code exchange}.
     *
     * @param exchange the source stream/exchange (an {@code EventStreams} constant)
     * @return how many messages were re-driven
     */
    int replay(String exchange);
}
