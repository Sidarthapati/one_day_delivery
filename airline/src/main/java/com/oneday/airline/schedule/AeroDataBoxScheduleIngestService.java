package com.oneday.airline.schedule;

import com.oneday.airline.config.AeroDataBoxProperties;
import com.oneday.airline.config.AirlineProperties;
import com.oneday.airline.config.ClockConfig;
import com.oneday.airline.provider.aerodatabox.AeroDataBoxClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * The "monthly cron" that fetches the forward flight schedule from AeroDataBox and populates our local
 * {@code flight_leg} store, so flight <em>selection</em> always reads the DB (zero vendor units) — the
 * schedule feed is the only place we spend AeroDataBox units besides the daily disruption poll.
 *
 * <p>Only active when {@code airline.aerodatabox.enabled=true}; otherwise the synthetic
 * {@code ConsolidatorLegRollForwardJob} keeps the mock schedule fresh instead. Idempotent per
 * {@code (flight_no, flight_date)} via upsert, so a re-run (or the admin trigger) never duplicates.
 * Fixed lanes → the schedule is a stable repeating pattern, so an occasional refresh is enough; the
 * daily disruption poll corrects same-day cancellations/retimes.</p>
 */
@Service
@ConditionalOnProperty(prefix = "airline.aerodatabox", name = "enabled", havingValue = "true")
public class AeroDataBoxScheduleIngestService {

    private static final Logger log = LoggerFactory.getLogger(AeroDataBoxScheduleIngestService.class);
    private static final int DEFAULT_CAPACITY_KG = 2000;

    // Upsert into the same flight_leg store the read path uses; append/refresh, never lose history of a date.
    private static final String UPSERT = """
            INSERT INTO flight_leg (flight_no, flight_date, carrier, origin_hub, dest_hub,
                                    departure_at, arrival_at, capacity_kg, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED')
            ON CONFLICT (flight_no, flight_date) DO UPDATE SET
                carrier = EXCLUDED.carrier, origin_hub = EXCLUDED.origin_hub, dest_hub = EXCLUDED.dest_hub,
                departure_at = EXCLUDED.departure_at, arrival_at = EXCLUDED.arrival_at,
                capacity_kg = EXCLUDED.capacity_kg
            """;

    private final AeroDataBoxClient client;
    private final AeroDataBoxProperties aeroProps;
    private final AirlineProperties airlineProps;
    private final JdbcTemplate consolidatorJdbcTemplate;

    public AeroDataBoxScheduleIngestService(AeroDataBoxClient client, AeroDataBoxProperties aeroProps,
                                            AirlineProperties airlineProps,
                                            @Qualifier("consolidatorJdbcTemplate") JdbcTemplate consolidatorJdbcTemplate) {
        this.client = client;
        this.aeroProps = aeroProps;
        this.airlineProps = airlineProps;
        this.consolidatorJdbcTemplate = consolidatorJdbcTemplate;
    }

    /** Monthly refresh of the forward window (03:00 IST on the 1st). Admin can also trigger via the API. */
    @Scheduled(cron = "${airline.schedule-ingest-cron:0 0 3 1 * *}", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        int n = refresh();
        log.info("AeroDataBox schedule ingest (scheduled) upserted {} legs", n);
    }

    /** Fetch every serviceable lane's forward schedule and upsert into flight_leg. Returns rows written. */
    public int refresh() {
        Set<String> airports = airlineProps.getCities().keySet();   // the 5 grid cities' IATA hub codes
        if (airports.isEmpty()) {
            log.warn("AeroDataBox ingest: no airports configured (airline.cities empty) — nothing to do");
            return 0;
        }
        // Real mode owns the schedule: clear the synthetic seed first so selection never picks a fake
        // SIM-CONSOLIDATOR flight alongside the real ones. (Simple delete-then-ingest; fine for v1.)
        int removed = consolidatorJdbcTemplate.update("DELETE FROM flight_leg WHERE carrier = 'SIM-CONSOLIDATOR'");
        if (removed > 0) {
            log.info("Cleared {} synthetic seed legs before the real AeroDataBox ingest", removed);
        }
        int written = 0;
        LocalDate today = LocalDate.now(ClockConfig.IST);
        for (String origin : airports) {
            for (int dayOffset = 0; dayOffset < aeroProps.getScheduleHorizonDays(); dayOffset++) {
                LocalDate date = today.plusDays(dayOffset);
                written += ingestAirportDay(origin, date, airports);
            }
        }
        return written;
    }

    private int ingestAirportDay(String origin, LocalDate date, Set<String> serviceableAirports) {
        int written = 0;
        // FIDS windows are capped at 12h, so a day is two calls.
        for (LocalTime windowStart : List.of(LocalTime.MIN, LocalTime.NOON)) {
            LocalDateTime from = date.atTime(windowStart);
            LocalDateTime to = from.plusHours(12);
            throttle();
            for (AeroDataBoxClient.DepartureRow row : client.departures(origin, from, to)) {
                // Keep only our own lanes (departures to another serviceable hub).
                if (origin.equals(row.destIata()) || !serviceableAirports.contains(row.destIata())) {
                    continue;
                }
                // Real departure + arrival instants come straight from AeroDataBox — no block estimate.
                consolidatorJdbcTemplate.update(UPSERT,
                        row.flightNo(), row.flightDate(), carrierOf(row.flightNo()), origin, row.destIata(),
                        Timestamp.from(row.departureUtc()), Timestamp.from(row.arrivalUtc()), DEFAULT_CAPACITY_KG);
                written++;
            }
        }
        return written;
    }

    /** Respect a plan's requests/second cap between FIDS calls (no-op when the delay is 0). */
    private void throttle() {
        long delay = aeroProps.getInterCallDelayMs();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Leading letters of the flight number are the carrier code ("AI806" → "AI"). */
    private static String carrierOf(String flightNo) {
        int i = 0;
        while (i < flightNo.length() && Character.isLetter(flightNo.charAt(i))) {
            i++;
        }
        return i > 0 ? flightNo.substring(0, i) : "UNKNOWN";
    }
}
