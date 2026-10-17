/**
 * Full-day scenario runner: drives a set of B2B parcels through the entire intercity HUB_RETURN
 * chain to a terminal state, gating on the customer track between surfaces. Every transition is a
 * real backend call. Per the verified endpoint map ADMIN drives all hub/airline/DA/OTP endpoints;
 * shuttle endpoints resolve the agent city from the JWT so they run as the real shuttle agents; the
 * customer track (the one endpoint ADMIN cannot hit) is polled with the booking owner's own token.
 *
 * Scenarios (coverage-first, per the plan's Tier A/B):
 *   A1  DEL→HYD, prepaid, DA pickup + DA delivery  (the spine)
 *   A2  HYD→DEL, prepaid  (reverse lane, 2nd business, CSHYDDEL flights)
 *   B1  DEL→HYD, COD      (cash collected at drop)
 *
 * Prereq: `npm run seed` (writes artifacts/personas.json + a live SHIFT_1 roster in THIS backend
 * instance). Backend up (single instance, under caffeinate, with the 24h pickup window). See README.
 */
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ControlPlane, closeDb, env, resolveTask, resolveFlightBag, sleep, type CityCode, type Persona,
} from '../harness/index';
import { buildBooking, spreadCoords } from '../harness/factories';
import { CITY_UUID } from '../harness/constants';

interface Manifest {
  businesses: { city: CityCode; accountId: string; owner: Persona }[];
  shuttles: Persona[];
}

interface Scenario {
  label: string;
  origin: CityCode;
  dest: CityCode;
  cod?: number; // paise; set → COD path
}

interface Rig {
  cp: ControlPlane;
  adminToken: string;
  business: Record<CityCode, { accountId: string; owner: Persona; ownerToken: string }>;
  shuttle: Record<CityCode, { agent: Persona; token: string }>;
}

async function loadManifest(): Promise<Manifest> {
  const raw = await readFile(path.join(path.resolve(env.outDir), 'personas.json'), 'utf8');
  return JSON.parse(raw) as Manifest;
}

async function buildRig(): Promise<Rig> {
  const cp = new ControlPlane();
  const m = await loadManifest();
  const adminToken = await cp.admin();

  const business = {} as Rig['business'];
  for (const b of m.businesses) {
    const ownerToken = (await cp.login(b.owner.email, b.owner.password)).token;
    business[b.city] = { accountId: b.accountId, owner: b.owner, ownerToken };
  }
  // shuttles are seeded [DEL, HYD]; resolve by the persona's city.
  const shuttle = {} as Rig['shuttle'];
  for (const s of m.shuttles) {
    const city = (s.city ?? (s.email.includes('del') ? 'DEL' : 'HYD')) as CityCode;
    const token = (await cp.login(s.email, s.password)).token;
    shuttle[city] = { agent: s, token };
  }
  return { cp, adminToken, business, shuttle };
}

/** Drive one parcel origin→dest to DROPPED. Returns its final ref. */
async function runParcel(rig: Rig, s: Scenario, index: number): Promise<string> {
  const { cp, adminToken } = rig;
  const biz = rig.business[s.origin];
  const originShuttle = rig.shuttle[s.origin];
  const destShuttle = rig.shuttle[s.dest];
  const ownerToken = biz.ownerToken;
  const tag = `[${s.label}]`;
  const log = (msg: string) => console.log(`${tag} ${msg}`);

  const origin = spreadCoords(s.origin, 1, 100 + index)[0]!;
  const dest = spreadCoords(s.dest, 1, 200 + index)[0]!;

  // 1 ── BOOK → BOOKED → PICKUP_ASSIGNED.
  const body = buildBooking({
    b2bAccountId: biz.accountId, originCity: s.origin, destCity: s.dest, origin, dest, index, cod: s.cod,
  });
  const ref = (await cp.book({ ...biz.owner, token: ownerToken }, body)).shipment_ref;
  log(`BOOKED ${ref}${s.cod ? ` (COD ₹${(s.cod / 100).toFixed(0)})` : ''}`);
  await cp.waitForState(ref, ownerToken, 'PICKUP_ASSIGNED');

  // 2 ── Pickup DA: en-route/arrived + pickup OTP → PICKED_UP.
  const pickup = await pollTask(ref, 'PICKUP');
  await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/en-route`, undefined, { token: adminToken });
  await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/arrived`, undefined, { token: adminToken });
  await cp.api.post(`/internal/v1/shipments/${ref}/pickup-otp/verify`, { otp: await cp.peekPickupOtp(ref) }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'PICKED_UP');

  // 3 ── DA hub handoff (HUB_RETURN) → RETURNED_TO_HUB.
  await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/hub-handoff`, { parcel_scans: [ref] }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'RETURNED_TO_HUB');

  // 4 ── Origin hub receive → IN_TAKEOFF_BAG (sort ladder + per-parcel bag).
  await cp.api.post(`/hub/${CITY_UUID[s.origin]}/receive`, { shipment_ref: ref }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'IN_TAKEOFF_BAG');

  // 5 ── Seal bag → shuttle to airport → DISPATCHED_TO_AIRPORT.
  const bag = await pollFlightBag(ref);
  await cp.api.post(`/hub/${CITY_UUID[s.origin]}/bags/${bag.bagId}/seal`, undefined, { token: adminToken });
  await cp.api.post(`/shuttle/${originShuttle.agent.userId}/out-to-airport`, { bag_ids: [bag.bagId] }, { token: originShuttle.token });
  await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_AIRPORT');
  log(`bag ${bag.bagId.slice(0, 8)} on ${bag.flightNo} ${bag.flightDate} → airport`);

  // 6 ── AWB (async on seal) → GHA accepted → AT_AIRPORT.
  const awb = await pollAwbByBag(bag.bagId, adminToken);
  try {
    await cp.api.post(`/airline/flights/${bag.flightNo}/${bag.flightDate}/awb`, { awb_no: `AWB${Date.now()}` }, { token: adminToken });
  } catch { /* flight already stamped by an earlier parcel — cosmetic */ }
  await cp.api.post(`/airline/awb/${awb.id}/gha-accepted`, undefined, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'AT_AIRPORT');

  // 7 ── Flight time-travel → LANDED.
  await cp.departAndLand(bag.flightNo, bag.flightDate);
  await cp.waitForState(ref, ownerToken, 'LANDED');

  // 8 ── Dest shuttle collect → DISPATCHED_TO_HUB.
  await cp.api.post(`/shuttle/${destShuttle.agent.userId}/awbs/${awb.id}/collect-from-airport`, undefined, { token: destShuttle.token });
  await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_HUB');

  // 9 ── Dest hub receive → HUB_DELIVERY_ASSIGNED (auto HUB_RETURN delivery assign).
  await cp.api.post(`/hub/${CITY_UUID[s.dest]}/receive`, { shipment_ref: ref }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'HUB_DELIVERY_ASSIGNED');

  // 10 ── Dest DA hub-collect → COLLECTED_FROM_HUB, then delivery OTP → DROPPED.
  const delivery = await pollTask(ref, 'DELIVERY');
  await cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/hub-collect`, undefined, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'COLLECTED_FROM_HUB');
  await cp.api.post(`/internal/v1/shipments/${ref}/delivery-otp/verify`, { otp: await cp.peekDeliveryOtp(ref) }, { token: adminToken });
  await cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/drop-completed`, { cod_collected: !!s.cod }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'DROPPED');
  log(`→ DROPPED ✅${s.cod ? ' (COD collected)' : ''}`);
  return ref;
}

const SCENARIOS: Scenario[] = [
  { label: 'A1 DEL→HYD prepaid', origin: 'DEL', dest: 'HYD' },
  { label: 'A2 HYD→DEL prepaid', origin: 'HYD', dest: 'DEL' },
  { label: 'B1 DEL→HYD COD', origin: 'DEL', dest: 'HYD', cod: 150000 },
];

async function main() {
  const rig = await buildRig();
  const results: { label: string; ok: boolean; ref?: string; error?: string }[] = [];
  for (let i = 0; i < SCENARIOS.length; i++) {
    const s = SCENARIOS[i]!;
    try {
      const ref = await runParcel(rig, s, i + 1);
      results.push({ label: s.label, ok: true, ref });
    } catch (e) {
      results.push({ label: s.label, ok: false, error: e instanceof Error ? e.message : String(e) });
      console.error(`[${s.label}] FAILED:`, e instanceof Error ? e.message : e);
    }
  }
  console.log('\n════ full-day scenario summary ════');
  for (const r of results) console.log(`  ${r.ok ? '✅' : '❌'} ${r.label}${r.ref ? `  ${r.ref}` : ''}${r.error ? `  — ${r.error.slice(0, 80)}` : ''}`);
  const passed = results.filter((r) => r.ok).length;
  console.log(`  ${passed}/${results.length} scenarios reached DROPPED`);
  if (passed !== results.length) process.exitCode = 1;
}

// ── helpers ──────────────────────────────────────────────────────────────
async function pollTask(ref: string, type: string, tries = 30): Promise<{ taskId: string; daId: string }> {
  for (let i = 0; i < tries; i++) {
    const t = await resolveTask(ref, type);
    if (t) return t;
    await sleep(1000);
  }
  throw new Error(`no ${type} task for ${ref} after ${tries}s`);
}

async function pollFlightBag(ref: string, tries = 30) {
  for (let i = 0; i < tries; i++) {
    const b = await resolveFlightBag(ref);
    if (b) return b;
    await sleep(1000);
  }
  throw new Error(`no flight bag for ${ref} after ${tries}s`);
}

async function pollAwbByBag(bagId: string, token: string, tries = 40): Promise<{ id: string }> {
  const cp = new ControlPlane();
  for (let i = 0; i < tries; i++) {
    try {
      return await cp.api.get<{ id: string }>(`/airline/awb/by-bag/${bagId}`, { token });
    } catch {
      await sleep(1500); // AWB booked async off the BAG_SEALED RabbitMQ event.
    }
  }
  throw new Error(`no AWB for bag ${bagId} after ${tries} tries (RabbitMQ BAG_SEALED consumer?)`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(closeDb);
