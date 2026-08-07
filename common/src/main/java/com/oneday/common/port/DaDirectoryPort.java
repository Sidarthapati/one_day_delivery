package com.oneday.common.port;

import com.oneday.common.domain.Shift;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The day's DA roster, sourced from M1 (auth) without importing the auth module. Implemented in auth
 * over {@code users} + {@code da_profile}; consumed in grid (M3) so the nightly territory plan and the
 * dispatch shift-load know which DAs are on the clock for a given city + date + shift.
 *
 * <p>A DA is "available" when: their user is active with role {@code DELIVERY_ASSOCIATE} in the city,
 * they are assigned to the requested {@link Shift}, and the date falls within their contract window
 * ({@code contract_start_date <= date} and {@code contract_end_date} is null or {@code >= date}).</p>
 *
 * <p>Keyed by the {@code users.city_id} <em>code</em> (e.g. {@code "delhi"}), not the grid city UUID —
 * the grid-side roster adapter translates UUID → code before calling.</p>
 */
public interface DaDirectoryPort {

    List<UUID> availableDaIds(String cityId, LocalDate date, Shift shift);
}
