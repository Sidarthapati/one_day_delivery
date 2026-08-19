package com.oneday.airline.provider.aerodatabox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.airline.config.AeroDataBoxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin HTTP client for AeroDataBox — the real flight-schedule + status feed. Only instantiated when
 * {@code airline.aerodatabox.enabled=true} (see {@link AeroDataBoxProperties}); otherwise absent and
 * the synthetic consolidator provider stays primary.
 *
 * <p>The two parse methods are {@code static} and pure so they can be unit-tested against captured
 * sample JSON without any network. <b>The exact field mapping must be validated against the live API
 * when a key is first procured</b> — parsing is deliberately defensive (missing fields are skipped,
 * never throw) so a schema surprise degrades to "fewer legs", not a crash.</p>
 */
@Component
@ConditionalOnProperty(prefix = "airline.aerodatabox", name = "enabled", havingValue = "true")
public class AeroDataBoxClient {

    private static final Logger log = LoggerFactory.getLogger(AeroDataBoxClient.class);
    private static final DateTimeFormatter FIDS_WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RestClient http;
    private final long minRequestIntervalMs;
    private final Object rateGate = new Object();
    private long nextAllowedAt = 0L;

    public AeroDataBoxClient(AeroDataBoxProperties properties) {
        RestClient.Builder b = RestClient.builder()
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        org.springframework.boot.web.client.ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(java.time.Duration.ofSeconds(3))
                                .withReadTimeout(java.time.Duration.ofSeconds(10))))
                .baseUrl(properties.getBaseUrl());
        if (!properties.getApiKey().isBlank()) {
            b = b.defaultHeader("x-rapidapi-key", properties.getApiKey());
        }
        if (!properties.getRapidApiHost().isBlank()) {
            b = b.defaultHeader("x-rapidapi-host", properties.getRapidApiHost());
        }
        this.http = b.build();
        this.minRequestIntervalMs = properties.getMinRequestIntervalMs();
    }

    /**
     * Serialises all outbound calls to at most one per {@code minRequestIntervalMs} so neither the ingest
     * nor the status poll can breach the plan's ~2 req/s cap. Reserves the next send slot under a short
     * lock, then sleeps to it <em>without</em> holding the lock (concurrent callers queue in order).
     */
    private void rateLimit() {
        long sendAt;
        synchronized (rateGate) {
            sendAt = Math.max(System.currentTimeMillis(), nextAllowedAt);
            nextAllowedAt = sendAt + minRequestIntervalMs;
        }
        long sleep = sendAt - System.currentTimeMillis();
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** FIDS departures from {@code iata} within a ≤12h window (API limit). One row per scheduled departure. */
    public List<DepartureRow> departures(String iata, LocalDateTime fromLocal, LocalDateTime toLocal) {
        rateLimit();
        try {
            String body = http.get()
                    .uri("/flights/airports/iata/{iata}/{from}/{to}?direction=Departure&withLeg=true&withCancelled=false",
                            iata, fromLocal.format(FIDS_WINDOW), toLocal.format(FIDS_WINDOW))
                    .retrieve()
                    .body(String.class);
            return parseDepartures(MAPPER.readTree(body));
        } catch (Exception e) {
            log.error("AeroDataBox departures fetch failed for {} [{}..{}]: {}", iata, fromLocal, toLocal, e.getMessage());
            return List.of();
        }
    }

    /** Live status for one flight on a date — feeds the daily disruption poll. */
    public Optional<StatusRow> flightStatus(String flightNo, LocalDate date) {
        rateLimit();
        try {
            String body = http.get()
                    .uri("/flights/number/{number}/{date}", flightNo, date.toString())
                    .retrieve()
                    .body(String.class);
            return parseStatus(MAPPER.readTree(body), date);
        } catch (Exception e) {
            log.error("AeroDataBox status fetch failed for {} ({}): {}", flightNo, date, e.getMessage());
            return Optional.empty();
        }
    }

    // ── pure parsers (unit-tested) ────────────────────────────────────────────

    static List<DepartureRow> parseDepartures(JsonNode root) {
        JsonNode departures = root.path("departures");
        if (!departures.isArray()) {
            departures = root.isArray() ? root : MAPPER.createArrayNode();
        }
        List<DepartureRow> rows = new ArrayList<>();
        for (JsonNode d : departures) {
            // Drop codeshare rows so one physical flight isn't ingested several times under partner numbers.
            if ("IsCodeshared".equalsIgnoreCase(text(d, "codeshareStatus"))) {
                continue;
            }
            String flightNo = normalizeFlightNo(text(d, "number"));
            String destIata = text(d.path("arrival").path("airport"), "iata");
            Instant depUtc = instantOf(d.path("departure"));
            Instant arrUtc = instantOf(d.path("arrival"));
            if (flightNo != null && destIata != null && depUtc != null && arrUtc != null) {
                rows.add(new DepartureRow(flightNo, destIata, depUtc.atZone(IST).toLocalDate(), depUtc, arrUtc));
            }
        }
        return rows;
    }

    static Optional<StatusRow> parseStatus(JsonNode root, LocalDate queryDate) {
        List<JsonNode> legs = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(legs::add);
        } else if (root != null && !root.isMissingNode()) {
            legs.add(root);
        }
        if (legs.isEmpty()) {
            return Optional.empty();
        }
        // A flight number that straddles midnight returns MULTIPLE instances for one date: the one that
        // ARRIVED that morning (departed the prior evening) and the one DEPARTING that evening.
        // AeroDataBox's date query matches either endpoint (dateLocalRole=Both), so we must pick the
        // instance whose SCHEDULED DEPARTURE local date == the queried date — otherwise a red-eye reads
        // as its already-arrived prior-day instance. Fall back to the first row if none carry a local date.
        JsonNode leg = legs.stream()
                .filter(l -> queryDate.equals(scheduledDepartureLocalDate(l)))
                .findFirst()
                .orElse(legs.get(0));
        String status = text(leg, "status");
        Instant estDep = instantOf(leg.path("departure"));
        Instant estArr = instantOf(leg.path("arrival"));
        return Optional.of(new StatusRow(status == null ? "" : status, estDep, estArr));
    }

    /** The flight's scheduled departure date in the departure airport's local time — AeroDataBox's own
     *  query key — or null if absent. Disambiguates midnight-straddling instances of one flight number. */
    private static LocalDate scheduledDepartureLocalDate(JsonNode leg) {
        JsonNode local = leg.path("departure").path("scheduledTime").path("local");
        if (!local.isValueNode() || local.asText().length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(local.asText().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** "AI 806" / "ai806" → "AI806"; null-safe. */
    private static String normalizeFlightNo(String raw) {
        if (raw == null) return null;
        String stripped = raw.replaceAll("\\s+", "").toUpperCase();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() && !v.asText().isBlank() ? v.asText() : null;
    }

    /** Prefers a movement's revised (actual/estimated) UTC instant, falling back to scheduled. */
    private static Instant instantOf(JsonNode movement) {
        Instant revised = utcInstant(movement.path("revisedTime"));
        return revised != null ? revised : utcInstant(movement.path("scheduledTime"));
    }

    private static Instant utcInstant(JsonNode time) {
        String utc = time.path("utc").isValueNode() ? time.path("utc").asText() : null;
        if (utc == null || utc.isBlank()) return null;
        String normalized = utc.trim().replace(' ', 'T').replace("Z", "+00:00");
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** A scheduled departure: flight number, destination, IST flight date, and real UTC departure/arrival instants. */
    public record DepartureRow(String flightNo, String destIata, LocalDate flightDate,
                               Instant departureUtc, Instant arrivalUtc) {}

    /** A flight's current word: raw status string plus best-known departure/arrival instants. */
    public record StatusRow(String rawStatus, Instant estimatedDeparture, Instant estimatedArrival) {}
}
