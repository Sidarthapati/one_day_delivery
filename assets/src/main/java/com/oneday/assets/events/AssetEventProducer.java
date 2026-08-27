package com.oneday.assets.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.asset.AssetCustodyChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes M13's custody events on {@code oneday.asset.events} via the shared {@link EventPublisher}.
 * Listens for the in-process {@link AssetCustodyChanged} AFTER_COMMIT so the broker event fires only
 * once the ledger write has committed (mirrors M8's ScanRecorded → outbound pattern).
 */
@Component
public class AssetEventProducer {

    private final EventPublisher eventPublisher;

    AssetEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCustodyChanged(AssetCustodyChanged e) {
        eventPublisher.publish(EventStreams.ASSET_EVENTS, new AssetCustodyChangedEvent(
                e.eventId(),
                e.eventType().name(),
                AssetCustodyChangedEvent.SCHEMA_VERSION,
                e.occurredAt(),
                e.assetId(),
                e.assetTag(),
                e.cityId(),
                e.status().name(),
                e.fromHolderType() != null ? e.fromHolderType().name() : null,
                e.fromHolderId(),
                e.toHolderType() != null ? e.toHolderType().name() : null,
                e.toHolderId(),
                e.actorId()));
    }
}
