package com.oneday.common.port;

import java.util.Optional;

/**
 * City identity lookup sourced from M3 (grid) without importing the grid module. Implemented in grid
 * over {@code GridService}; consumed in auth (M1) so DA registration can store {@code users.city_id}
 * in the one canonical form the DA roster query ({@link DaDirectoryPort}) matches against — the city
 * <em>code</em> (e.g. {@code "mumbai"}), not the city UUID every other user type carries.
 */
public interface CityDirectoryPort {

    /**
     * Canonical city code for an identifier that may be a city code, an IATA alias (e.g. {@code "BOM"}),
     * or the city UUID. {@link Optional#empty()} if it resolves to no known city.
     */
    Optional<String> toCityCode(String cityIdOrCode);
}
