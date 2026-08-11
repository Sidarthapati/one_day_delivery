package com.oneday.airline.provider.aerodatabox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.airline.provider.aerodatabox.AeroDataBoxClient.DepartureRow;
import com.oneday.airline.provider.aerodatabox.AeroDataBoxClient.StatusRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the AeroDataBox JSON parsing to its documented shape (validated live on key procurement).
 * The parsers must be defensive: a missing field skips a row rather than throwing.
 */
class AeroDataBoxClientParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void parsesDepartures_realShape_flightNoDestAndUtcInstants() throws Exception {
        // Captured from the live AeroDataBox FIDS departures response (DEL, Aug 2026).
        List<DepartureRow> rows = AeroDataBoxClient.parseDepartures(json("""
            {
              "departures": [
                { "number": "AI 1793", "status": "Expected", "codeshareStatus": "IsOperator",
                  "departure": { "scheduledTime": { "utc": "2026-08-12 00:30Z", "local": "2026-08-12 06:00+05:30" } },
                  "arrival":   { "airport": { "iata": "HYD" },
                                 "scheduledTime": { "utc": "2026-08-12 02:50Z", "local": "2026-08-12 08:20+05:30" } } },
                { "number": "6E 6084", "codeshareStatus": "IsCodeshared",
                  "departure": { "scheduledTime": { "utc": "2026-08-12 01:00Z" } },
                  "arrival":   { "airport": { "iata": "BOM" }, "scheduledTime": { "utc": "2026-08-12 03:10Z" } } }
              ]
            }
            """));

        // Codeshare row dropped; operator row parsed with real dep/arr instants and IST flight date.
        assertThat(rows).containsExactly(new DepartureRow("AI1793", "HYD", LocalDate.of(2026, 8, 12),
                Instant.parse("2026-08-12T00:30:00Z"), Instant.parse("2026-08-12T02:50:00Z")));
    }

    @Test
    void skipsDepartureRowsMissingRequiredFields() throws Exception {
        List<DepartureRow> rows = AeroDataBoxClient.parseDepartures(json("""
            { "departures": [
                { "number": "AI806", "arrival": { "airport": { "iata": "BOM" } } },
                { "departure": { "scheduledTime": { "utc": "2026-08-11 00:30Z" } },
                  "arrival": { "airport": { "iata": "BOM" }, "scheduledTime": { "utc": "2026-08-11 02:30Z" } } }
            ] }
            """));

        assertThat(rows).isEmpty();   // first has no times, second has no flight number
    }

    @Test
    void parsesStatus_preferringRevisedOverScheduledInstants() throws Exception {
        Optional<StatusRow> row = AeroDataBoxClient.parseStatus(json("""
            [ { "number": "AI806", "status": "Delayed",
                "departure": { "scheduledTime": { "utc": "2026-08-11 00:30Z" }, "revisedTime": { "utc": "2026-08-11 01:15Z" } },
                "arrival":   { "scheduledTime": { "utc": "2026-08-11 02:30Z" }, "revisedTime": { "utc": "2026-08-11 03:15Z" } } } ]
            """));

        assertThat(row).isPresent();
        assertThat(row.get().rawStatus()).isEqualTo("Delayed");
        assertThat(row.get().estimatedDeparture()).isEqualTo(Instant.parse("2026-08-11T01:15:00Z"));
        assertThat(row.get().estimatedArrival()).isEqualTo(Instant.parse("2026-08-11T03:15:00Z"));
    }

    @Test
    void statusFallsBackToScheduledWhenNoRevisedTime() throws Exception {
        Optional<StatusRow> row = AeroDataBoxClient.parseStatus(json("""
            [ { "status": "Expected",
                "departure": { "scheduledTime": { "utc": "2026-08-11 00:30Z" } },
                "arrival":   { "scheduledTime": { "utc": "2026-08-11 02:30Z" } } } ]
            """));

        assertThat(row).isPresent();
        assertThat(row.get().estimatedDeparture()).isEqualTo(Instant.parse("2026-08-11T00:30:00Z"));
    }

    @Test
    void emptyStatusArrayYieldsEmpty() throws Exception {
        assertThat(AeroDataBoxClient.parseStatus(json("[]"))).isEmpty();
    }
}
