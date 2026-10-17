/**
 * Direct Postgres access for the two control-plane ops that have no API:
 *   1. bump b2b_accounts.credit_limit_paise (onboarding provisions credit=0)
 *   2. flight time-travel: set flight_instance.departure/arrival into the past, then force-poll
 * Everything else goes through the REST API.
 */
import { Pool } from 'pg';
import { env } from './env';

let pool: Pool | undefined;

function getPool(): Pool {
  if (!pool) pool = new Pool({ connectionString: env.db.url });
  return pool;
}

export async function closeDb(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = undefined;
  }
}

/** Raise a B2B account's credit limit so CREDIT-funded bookings pass the credit check. */
export async function bumpCreditLimit(accountId: string, limitPaise: number): Promise<void> {
  await getPool().query(
    'UPDATE b2b_accounts SET credit_limit_paise = $1 WHERE id = $2',
    [limitPaise, accountId],
  );
}

/**
 * Onboarding provisions credit=0 and rate_card_id=null; there is no HTTP path for either. Booking
 * needs BOTH a positive credit limit (for funding_source=CREDIT) and a rate card (to price the lane),
 * so set them directly. rateCardId = the seeded B2B card (pricing V2_4).
 */
export async function setB2bRateCardAndCredit(
  accountId: string,
  limitPaise: number,
  rateCardId: string,
): Promise<void> {
  await getPool().query(
    'UPDATE b2b_accounts SET credit_limit_paise = $1, rate_card_id = $2 WHERE id = $3',
    [limitPaise, rateCardId, accountId],
  );
}

/**
 * Time-travel a flight so the clock-based poll flips it. Set departure just past and arrival now-ish;
 * follow with POST /internal/dev/airline/force-poll to advance SCHEDULED→DEPARTED→LANDED at once.
 */
export async function timeTravelFlight(flightNo: string, flightDate: string): Promise<void> {
  // Reset to SCHEDULED as well: the poll only notifies parcels when the flight TRANSITIONS, so a
  // flight already progressed to LANDED by an earlier run would never notify a parcel that joined it
  // later. Resetting + pushing the times into the past makes the next force-poll re-progress it and
  // notify all parcels currently on the flight.
  await getPool().query(
    `UPDATE flight_instance
        SET status    = 'SCHEDULED',
            departure = now() - interval '2 minutes',
            arrival   = now() - interval '1 minute'
      WHERE flight_no = $1 AND flight_date = $2`,
    [flightNo, flightDate],
  );
}

/**
 * Reset today's flights on a lane to a feasible future SCHEDULED state before a booking. Each run's
 * `departAndLand` pushes its flight's departure into the PAST to land it; a later booking on the same
 * lane then finds no future-departing flight and the M5 cron-meeting constraint refuses to assign it
 * (parcel sits at BOOKED). Re-futuring the lane's flights before each booking makes the harness
 * repeatable across scenarios and re-runs on the same day. departAndLand still lands the specific
 * flight afterwards. Future window is wide (dep +5h) so the cutoff (dep − hub lead) stays ahead.
 */
export async function resetLaneFlightsToFuture(originIata: string, destIata: string): Promise<void> {
  await getPool().query(
    `UPDATE flight_instance
        SET status    = 'SCHEDULED',
            departure = now() + interval '5 hours',
            arrival   = now() + interval '6 hours'
      WHERE flight_no LIKE $1 AND flight_date = CURRENT_DATE`,
    [`CS${originIata}${destIata}%`],
  );
}

/** Look up a user id by email — used to make persona registration idempotent (re-runnable seed). */
export async function findUserIdByEmail(email: string): Promise<string | null> {
  const rows = await getPool().query<{ id: string }>('SELECT id FROM users WHERE email = $1', [email]);
  return rows.rows[0]?.id ?? null;
}

/** True if any of these DAs already has an APPROVED hex assignment for the date (skip re-planning). */
export async function hasApprovedAssignments(daIds: string[], date: string): Promise<boolean> {
  if (daIds.length === 0) return false;
  const rows = await getPool().query<{ n: string }>(
    `SELECT count(*)::int AS n FROM da_hex_assignment
      WHERE da_id = ANY($1::uuid[]) AND valid_date = $2 AND status = 'APPROVED'`,
    [daIds, date],
  );
  return Number(rows.rows[0]?.n ?? 0) > 0;
}

/** Resolve a DA task (id + da_id) for a shipment ref by task type (PICKUP / DELIVERY / …). */
export async function resolveTask(
  ref: string,
  taskType: string,
): Promise<{ taskId: string; daId: string } | null> {
  const rows = await getPool().query<{ id: string; da_id: string }>(
    `SELECT dq.id, dq.da_id
       FROM dispatch_queue dq JOIN shipments s ON dq.shipment_id = s.id
      WHERE s.shipment_ref = $1 AND dq.task_type = $2
      ORDER BY dq.created_at DESC LIMIT 1`,
    [ref, taskType],
  );
  const r = rows.rows[0];
  return r ? { taskId: r.id, daId: r.da_id } : null;
}

/** Resolve the flight bag a parcel is in (id + assigned flight) — the sort picks the flight. */
export async function resolveFlightBag(
  ref: string,
): Promise<{ bagId: string; flightNo: string; flightDate: string; status: string } | null> {
  const rows = await getPool().query<{
    id: string; flight_no: string; flight_date: string; status: string;
  }>(
    `SELECT b.id, b.flight_no, to_char(b.flight_date, 'YYYY-MM-DD') AS flight_date, b.status
       FROM flight_bag b JOIN flight_bag_item i ON i.bag_id = b.id
      WHERE i.shipment_ref = $1 AND i.removed_at IS NULL
      ORDER BY i.added_at DESC LIMIT 1`,
    [ref],
  );
  const r = rows.rows[0];
  return r ? { bagId: r.id, flightNo: r.flight_no, flightDate: r.flight_date, status: r.status } : null;
}

/** Escape hatch for ad-hoc control-plane SQL. */
export async function query<T = unknown>(text: string, params: unknown[] = []): Promise<T[]> {
  const res = await getPool().query(text, params);
  return res.rows as T[];
}
