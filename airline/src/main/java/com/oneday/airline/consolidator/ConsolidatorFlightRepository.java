package com.oneday.airline.consolidator;

import com.oneday.airline.service.exception.ConsolidatorLegNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only access to the consolidator's (mocked) {@code flight_leg} table — plain JDBC, not JPA,
 * since we don't own that schema (see {@link ConsolidatorDataSourceConfig}).
 */
@Repository
public class ConsolidatorFlightRepository {

    private static final RowMapper<ConsolidatorFlightLeg> ROW_MAPPER = (rs, rowNum) -> new ConsolidatorFlightLeg(
            rs.getString("flight_no"),
            rs.getString("carrier"),
            rs.getString("origin_hub"),
            rs.getString("dest_hub"),
            rs.getObject("flight_date", LocalDate.class),
            toInstant(rs.getTimestamp("departure_at")),
            toInstant(rs.getTimestamp("arrival_at")),
            rs.getInt("capacity_kg"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("estimated_departure_at")),
            toInstant(rs.getTimestamp("estimated_arrival_at")));

    private final JdbcTemplate jdbcTemplate;

    public ConsolidatorFlightRepository(@Qualifier("consolidatorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Every leg on a lane for a given date — the consolidator's schedule/availability answer. */
    public List<ConsolidatorFlightLeg> findLegs(String originHub, String destHub, LocalDate flightDate) {
        return jdbcTemplate.query(
                "SELECT * FROM flight_leg WHERE origin_hub = ? AND dest_hub = ? AND flight_date = ?",
                ROW_MAPPER, originHub, destHub, flightDate);
    }

    /** One specific leg by (flightNo, date) — the identity a booking/status check resolves against. */
    public ConsolidatorFlightLeg findLeg(String flightNo, LocalDate flightDate) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM flight_leg WHERE flight_no = ? AND flight_date = ?",
                    ROW_MAPPER, flightNo, flightDate);
        } catch (EmptyResultDataAccessException e) {
            throw new ConsolidatorLegNotFoundException(flightNo, flightDate);
        }
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
