package com.oneday.grid.events;

public final class KafkaTopics {

    // Published by M3 → consumed by M5, call-centre (M11)
    public static final String NO_DA_ALERT = "grid.no_da_alert";

    // Published by M3 → consumed by M5, M10 (SLA)
    public static final String TILE_OVERLOAD_ALERT = "grid.tile_overload_alert";

    // Published by M4 (Orders) → consumed by M3
    public static final String TILE_QUEUE_DEPTH = "orders.tile_queue_depth";

    private KafkaTopics() {}
}
