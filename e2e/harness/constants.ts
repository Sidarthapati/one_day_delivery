/**
 * Ground-truth constants for the full-day E2E, verified against the backend during planning.
 * See docs/e2e/FULL-DAY-E2E.md. Keep this the single source for city/hub/coord facts.
 */

/** Seeded superuser (auth DataInitializer). ADMIN is allowed on every endpoint except B2B booking. */
export const ADMIN = { email: 'admin@oneday.in', password: 'Admin1234!' } as const;

/** Grid city UUID == hub_id == fleet cityId. From app application.yml grid.cities / routing V6_11. */
export const CITY_UUID = {
  DEL: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
  BOM: '550e8400-e29b-41d4-a716-446655440000',
  BLR: '6ba7b810-9dad-11d1-80b4-00c04fd430c8',
  HYD: '6ba7b811-9dad-11d1-80b4-00c04fd430c8',
  MAA: '6ba7b812-9dad-11d1-80b4-00c04fd430c8',
} as const;

/** Grid seeder city slug (used in /api/grid/{cityCode}/... paths and serviceable-at city_code). */
export const CITY_SLUG = { DEL: 'delhi', HYD: 'hyderabad' } as const;

/** Seeded B2B rate card (pricing V2_4, ACTIVE). Onboarding leaves rate_card_id null; booking needs one. */
export const DEMO_B2B_RATE_CARD = 'c0000000-0000-0000-0000-000000000001';

/** DA attendance geofence centres (dispatch.attendance.hub-locations); 500m radius. */
export const HUB_GEOFENCE = {
  DEL: { lat: 28.5, lon: 77.15 },
  HYD: { lat: 17.41397, lon: 78.34436 },
} as const;

/** Serviceable bounding boxes from the seeded H3 geojson (res 8). Pins must land inside + on active hexes. */
export const SERVICEABLE_BOX = {
  DEL: { lonMin: 76.84, lonMax: 77.35, latMin: 28.4, latMax: 28.88 },
  HYD: { lonMin: 78.25, lonMax: 78.62, latMin: 17.24, latMax: 17.6 },
} as const;

/**
 * Consolidator flight numbers (schema consolidator_mock.flight_leg, seeded today..+89d).
 * Format CS<ORIGIN><DEST><HH>, slots 06/12/18/22 IST. Status is deterministic per (flight_no,date):
 * some are CANCELLED/DELAYED — pick a SCHEDULED one via GET /airline/lanes/{o}/{d}/schedule?date=.
 */
export const FLIGHTS = {
  DEL_HYD: ['CSDELHYD06', 'CSDELHYD12', 'CSDELHYD18', 'CSDELHYD22'],
  HYD_DEL: ['CSHYDDEL06', 'CSHYDDEL12', 'CSHYDDEL18', 'CSHYDDEL22'],
} as const;

export type CityCode = 'DEL' | 'HYD';
export type Shift = 'SHIFT_1' | 'SHIFT_2';

/** Per-app localStorage keys (oneday-web) for seeding sessions without a UI login. */
export const WEB_SESSION_KEYS = {
  customer: { token: 'od_token', session: 'od_session' },
  business: { token: 'od_business_token', session: 'od_business_session' },
  hub: { token: 'od_hub_token', session: 'od_hub_session' },
  station: { token: 'od_station_token', session: 'od_station_session' },
  airline: { token: 'od_gha_token', session: 'od_gha_session' },
  admin: { token: 'od_admin_token', session: 'od_admin_session' },
} as const;
