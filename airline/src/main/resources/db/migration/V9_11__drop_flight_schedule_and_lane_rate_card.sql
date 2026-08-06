-- The freight-consolidator integration (read-only, direct DB access, see db/migration-consolidator)
-- replaces both of these: schedule data now comes from the consolidator's own dated flight_leg
-- calendar, and lane pricing from their lane_rate table. Neither is owned by us anymore.
DROP TABLE flight_schedule;
DROP TABLE lane_rate_card;
