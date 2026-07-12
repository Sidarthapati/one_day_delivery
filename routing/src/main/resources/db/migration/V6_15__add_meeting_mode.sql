-- Per-city gate for M6 (van meeting points). VAN_MEETING = full M6 routing (default, all 5
-- launch cities). HUB_RETURN = no M6: the DA periodically returns to the hub instead of
-- meeting a van; the hub is the rendezvous and hub_return_interval_minutes sets the cadence.
ALTER TABLE city_fleet_config
    ADD COLUMN meeting_mode               VARCHAR(20) NOT NULL DEFAULT 'VAN_MEETING',
    ADD COLUMN hub_return_interval_minutes INT;   -- HUB_RETURN only; null → code default
