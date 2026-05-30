package com.oneday.grid.events;

public final class KafkaTopics {

    // M3's own produced events now live on the consolidated common topic
    // com.oneday.common.kafka.KafkaTopics.GRID_EVENTS ("oneday.grid.events"),
    // discriminated by com.oneday.common.kafka.enums.GridEventType.

    // Published by M4 (Orders) → consumed by M3. M4's topic, kept here for the consumer binding.
    public static final String TILE_QUEUE_DEPTH = "orders.tile_queue_depth";

    private KafkaTopics() {}
}
