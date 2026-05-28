package com.oneday.common.domain;

import jakarta.persistence.MappedSuperclass;

/**
 * Semantic marker for entities whose rows are updated after initial insert
 * (e.g. Shipment state changes, B2bAccount balance adjustments).
 *
 * <p>{@link BaseEntity} now owns {@code createdAt} and {@code updatedAt} via
 * {@code @CreationTimestamp} / {@code @UpdateTimestamp}. This class exists solely
 * to make the intent explicit at the type level — extend it for mutable entities,
 * extend {@link BaseEntity} directly (or use a standalone entity) for append-only
 * records such as {@code ShipmentStateHistory}.</p>
 */
@MappedSuperclass
public abstract class MutableBaseEntity extends BaseEntity {
    // updatedAt is inherited from BaseEntity (@UpdateTimestamp).
    // No additional fields or lifecycle callbacks needed here.
}
