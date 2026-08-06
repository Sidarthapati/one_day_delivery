package com.oneday.airline.consolidator;

import com.oneday.airline.service.exception.ConsolidatorRateNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the consolidator's (mocked) {@code lane_rate} table — plain JDBC, not JPA,
 * since we don't own that schema (see {@link ConsolidatorDataSourceConfig}).
 */
@Repository
public class ConsolidatorRateRepository {

    private static final RowMapper<ConsolidatorLaneRate> ROW_MAPPER = (rs, rowNum) -> new ConsolidatorLaneRate(
            rs.getString("origin_hub"),
            rs.getString("dest_hub"),
            rs.getLong("min_charge_paise"),
            rs.getLong("terminal_handling_paise"),
            rs.getLong("rate_below_45kg_paise_per_kg"),
            rs.getLong("rate_q45_paise_per_kg"),
            rs.getLong("rate_q100_paise_per_kg"),
            rs.getLong("rate_q300_paise_per_kg"),
            rs.getLong("rate_q500_paise_per_kg"),
            rs.getLong("rate_q1000_paise_per_kg"));

    private final JdbcTemplate jdbcTemplate;

    public ConsolidatorRateRepository(@Qualifier("consolidatorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The lane's currently ACTIVE rate — mirrors the old {@code lane_rate_card} lookup. */
    public ConsolidatorLaneRate findActiveRate(String originHub, String destHub) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM lane_rate WHERE origin_hub = ? AND dest_hub = ? AND status = 'ACTIVE'",
                    ROW_MAPPER, originHub, destHub);
        } catch (EmptyResultDataAccessException e) {
            throw new ConsolidatorRateNotFoundException(originHub, destHub);
        }
    }
}
