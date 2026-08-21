package com.oneday.common.port;

import java.util.Optional;

/**
 * The on-duty desk contact for a non-DA custody stage — who to call when a parcel is sitting in a hub
 * or moving through the airline. Implemented in auth (M1) over {@code users} (role + city_id); consumed
 * by the SLA control tower's "who to call" so hub and air legs resolve a phone, the way
 * {@link CourierOnShipmentPort} already does for the DA legs.
 *
 * <p>There is no per-hub or per-flight staffing table in v1, so these resolve the representative
 * on-duty staffer by role: a {@code HUB_OPERATOR} (or {@code STATION_MANAGER}) in the hub's city, and
 * an {@code AIRLINE_GHA} nationally. It's a desk contact, not a named individual assignment.</p>
 */
public interface StageContactPort {

    /** Hub desk for a city (IATA code): its on-duty HUB_OPERATOR, else a STATION_MANAGER. */
    Optional<Contact> hubDesk(String cityCode);

    /** The airline ground-handling (GHA) desk — national, not city-scoped. */
    Optional<Contact> ghaDesk();

    record Contact(String name, String phone, String role) {}
}
