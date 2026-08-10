package com.oneday.airline.provider.aerodatabox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.airline.provider.aerodatabox.AeroDataBoxClient.DepartureRow;
import com.oneday.airline.provider.aerodatabox.AeroDataBoxClient.StatusRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
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
    void parsesDepartures_normalisingFlightNoAndReadingLocalTimeAndDest() throws Exception {
        List<DepartureRow> rows = AeroDataBoxClient.parseDepartures(json("""
            {
              "departures": [
                { "number": "AI 806",
                  "movement": { "airport": { "iata": "BOM", "name": "Mumbai" },
                                "scheduledTime": { "utc": "2026-08-11 00:30Z", "local": "2026-08-11 06:00+05:30" } } },
                { "number": "6E 123",
                  "movement": { "airport": { "iata": "BLR" },
                                "scheduledTime": { "local": "2026-08-11 09:15+05:30" } } }
              ]
            }
            """));

        assertThat(rows).containsExactly(
                new DepartureRow("AI806", "BOM", LocalTime.of(6, 0)),
                new DepartureRow("6E123", "BLR", LocalTime.of(9, 15)));
    }

    @Test
    void skipsDepartureRowsMissingRequiredFields() throws Exception {
        List<DepartureRow> rows = AeroDataBoxClient.parseDepartures(json("""
            { "departures": [
                { "number": "AI806", "movement": { "airport": { "iata": "BOM" } } },
                { "movement": { "airport": { "iata": "BOM" }, "scheduledTime": { "local": "2026-08-11 06:00+05:30" } } }
            ] }
            """));

        assertThat(rows).isEmpty();   // first has no time, second has no flight number
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
