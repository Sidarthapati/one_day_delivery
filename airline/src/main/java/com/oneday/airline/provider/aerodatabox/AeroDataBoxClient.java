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
import java.time.LocalTime;
import java.time.OffsetDateTime;
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

    private final RestClient http;

    public AeroDataBoxClient(AeroDataBoxProperties properties) {
        RestClient.Builder b = RestClient.builder().baseUrl(properties.getBaseUrl());
        if (!properties.getApiKey().isBlank()) {
            b = b.defaultHeader("x-rapidapi-key", properties.getApiKey());
        }
        if (!properties.getRapidApiHost().isBlank()) {
            b = b.defaultHeader("x-rapidapi-host", properties.getRapidApiHost());
        }
        this.http = b.build();
    }

    /** FIDS departures from {@code iata} within a ≤12h window (API limit). One row per scheduled departure. */
    public List<DepartureRow> departures(String iata, LocalDateTime fromLocal, LocalDateTime toLocal) {
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
        try {
            String body = http.get()
                    .uri("/flights/number/{number}/{date}", flightNo, date.toString())
                    .retrieve()
                    .body(String.class);
            return parseStatus(MAPPER.readTree(body));
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
            String flightNo = normalizeFlightNo(text(d, "number"));
            String destIata = text(d.path("movement").path("airport"), "iata");
            LocalTime depTime = localTimeOf(d.path("movement").path("scheduledTime"));
            if (flightNo != null && destIata != null && depTime != null) {
                rows.add(new DepartureRow(flightNo, destIata, depTime));
            }
        }
        return rows;
    }

    static Optional<StatusRow> parseStatus(JsonNode root) {
        JsonNode leg = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
        if (leg == null || leg.isMissingNode()) {
            return Optional.empty();
        }
        String status = text(leg, "status");
        Instant estDep = instantOf(leg.path("departure"));
        Instant estArr = instantOf(leg.path("arrival"));
        return Optional.of(new StatusRow(status == null ? "" : status, estDep, estArr));
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

    /** Reads a {revisedTime|scheduledTime}.local time-of-day, tolerating "..HH:mm+05:30" or ISO forms. */
    private static LocalTime localTimeOf(JsonNode scheduledTime) {
        String local = text(scheduledTime, "local");
        if (local == null) return null;
        String normalized = local.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized).toLocalTime();
        } catch (Exception ignore) {
            try {
                return LocalDateTime.parse(normalized).toLocalTime();
            } catch (Exception ignore2) {
                return null;
            }
        }
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

    /** A scheduled departure: flight number, destination airport, and local departure time-of-day. */
    public record DepartureRow(String flightNo, String destIata, LocalTime departureLocal) {}

    /** A flight's current word: raw status string plus best-known departure/arrival instants. */
    public record StatusRow(String rawStatus, Instant estimatedDeparture, Instant estimatedArrival) {}
}
