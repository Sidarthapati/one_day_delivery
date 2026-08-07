package com.oneday.airline.consolidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the mocked consolidator schedule's rolling ~90-day window fresh as "today" advances,
 * without ever mutating an existing row — multiple developers may run the assembled app against
 * the same shared dev DB (see project CLAUDE.md), so a job that mutated existing legs in place
 * could race across instances. This only appends further-out dates the original seed
 * ({@code V3__seed_flight_legs.sql}) hadn't reached yet, via the exact same generation SQL, so a
 * freshly-seeded and a long-running mock always agree.
 *
 * <p>{@code @Profile("!prod")} mirrors the rest of M9's simulated-provider infrastructure — a real
 * consolidator integration has no need for us to invent their own future schedule.
 */
@Component
@Profile("!prod")
class ConsolidatorLegRollForwardJob {

    private static final Logger log = LoggerFactory.getLogger(ConsolidatorLegRollForwardJob.class);

    // Same generation logic as db/migration-consolidator/V3__seed_flight_legs.sql — kept identical
    // so a freshly-migrated schema and one that's been rolling forward for months never disagree.
    private static final String ROLL_FORWARD_SQL = """
            WITH cities(code) AS (VALUES ('DEL'), ('BOM'), ('BLR'), ('HYD'), ('MAA')),
            lanes AS (
                SELECT o.code AS origin_hub, d.code AS dest_hub
                FROM cities o CROSS JOIN cities d
                WHERE o.code <> d.code
            ),
            slots(departure_time, arrival_time) AS (
                VALUES
                    ('06:00'::time, '08:00'::time),
                    ('12:00'::time, '14:00'::time),
                    ('18:00'::time, '20:00'::time),
                    ('22:00'::time, '00:00'::time)
            ),
            dates AS (
                SELECT generate_series(CURRENT_DATE, CURRENT_DATE + 89, INTERVAL '1 day')::date AS flight_date
            ),
            legs AS (
                SELECT
                    l.origin_hub, l.dest_hub, 'SIM-CONSOLIDATOR' AS carrier,
                    'CS' || l.origin_hub || l.dest_hub || TO_CHAR(s.departure_time, 'HH24') AS flight_no,
                    d.flight_date, s.departure_time, s.arrival_time,
                    2000 AS capacity_kg,
                    (('x' || substr(md5(
                        'CS' || l.origin_hub || l.dest_hub || TO_CHAR(s.departure_time, 'HH24') || d.flight_date::text
                    ), 1, 8))::bit(32)::int % 100 + 100) % 100 AS bucket
                FROM lanes l CROSS JOIN slots s CROSS JOIN dates d
            )
            INSERT INTO flight_leg (
                flight_no, flight_date, carrier, origin_hub, dest_hub, departure_at, arrival_at, capacity_kg,
                status, estimated_departure_at, estimated_arrival_at
            )
            SELECT
                flight_no, flight_date, carrier, origin_hub, dest_hub,
                (flight_date + departure_time) AT TIME ZONE 'Asia/Kolkata',
                (CASE WHEN arrival_time > departure_time THEN flight_date ELSE flight_date + 1 END + arrival_time)
                    AT TIME ZONE 'Asia/Kolkata',
                capacity_kg,
                CASE WHEN bucket < 3 THEN 'CANCELLED' WHEN bucket < 10 THEN 'DELAYED' ELSE 'SCHEDULED' END,
                CASE WHEN bucket >= 3 AND bucket < 10
                     THEN (flight_date + departure_time) AT TIME ZONE 'Asia/Kolkata' + ((30 + bucket % 90) || ' minutes')::interval
                     ELSE NULL END,
                CASE WHEN bucket >= 3 AND bucket < 10
                     THEN (CASE WHEN arrival_time > departure_time THEN flight_date ELSE flight_date + 1 END + arrival_time)
                              AT TIME ZONE 'Asia/Kolkata' + ((30 + bucket % 90) || ' minutes')::interval
                     ELSE NULL END
            FROM legs
            ON CONFLICT (flight_no, flight_date) DO NOTHING
            """;

    private final JdbcTemplate consolidatorJdbcTemplate;

    ConsolidatorLegRollForwardJob(@Qualifier("consolidatorJdbcTemplate") JdbcTemplate consolidatorJdbcTemplate) {
        this.consolidatorJdbcTemplate = consolidatorJdbcTemplate;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Kolkata")
    void rollForward() {
        int inserted = consolidatorJdbcTemplate.update(ROLL_FORWARD_SQL);
        if (inserted > 0) {
            log.info("Consolidator mock: appended {} new flight legs to keep the 90-day window fresh", inserted);
        }
    }
}
