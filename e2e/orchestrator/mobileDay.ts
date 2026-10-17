/**
 * Full-day HYBRID runner — the MOBILE counterpart of runDay.ts. Every persona that has a screen in
 * the DRIVER APP is driven through the real app with Maestro (DA pickup, shuttle-out, shuttle-collect,
 * DA delivery); the screenless middle (origin/dest hub receive+seal, airline AWB+GHA, the flight
 * clock) runs on the control plane, exactly as runDay does. Gates on the customer track between legs.
 *
 * This is the mobile leg of the hybrid E2E; the web-console leg is playwright/tests/a1-spine.web.spec.
 * A parcel that reaches DROPPED here has been pickup-scanned, OTP'd, shuttled and delivered through the
 * actual Android app.
 *
 * Prereq: `npm run seed` once (personas.json); backend + broker up; an Android emulator with the
 * driver dev-client (EXPO_PUBLIC_MOCK_SCANNER + ALLOW_EMULATOR) on Metro; Maestro on PATH. Presence is
 * re-established here (it is in-memory and lost on a backend relaunch). See maestro/README.md.
 *
 * Run from e2e/: npx tsx orchestrator/mobileDay.ts
 */
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ControlPlane, closeDb, env, resolveTask, resolveFlightBag, resetLaneFlightsToFuture, sleep,
  daPickup, daDelivery, shuttleOut, shuttleCollect, type CityCode, type Persona, type Shift,
} from '../harness/index';
import { buildBooking, spreadCoords } from '../harness/factories';
import { CITY_UUID } from '../harness/constants';

interface Manifest {
  das: (Persona & { userId: string; shift: Shift })[];
  shuttles: Persona[];
  businesses: { city: CityCode; accountId: string; owner: Persona }[];
}

interface Scenario { label: string; origin: CityCode; dest: CityCode; cod?: number }

interface Rig {
  cp: ControlPlane;
  adminToken: string;
  daByUserId: Map<string, Persona>;
  shuttle: Record<CityCode, Persona>;
  business: Record<CityCode, { accountId: string; owner: Persona; ownerToken: string }>;
}

const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

/**
 * Retry a control-plane mutation on a transient backend 5xx / DB-pool timeout. The mobile legs take
 * minutes, during which the backend's remote-DB (Singapore) pool can let idle connections go stale;
 * the next call then times out acquiring one (HikariPool "Connection is not available"). A retry
 * re-establishes it. Only transient errors are retried; real 4xx bubble straight up.
 */
async function retryTransient<T>(fn: () => Promise<T>, label: string, tries = 6): Promise<T> {
  let last: unknown;
  for (let i = 0; i < tries; i++) {
    try { return await fn(); }
    catch (e) {
      last = e;
      const msg = e instanceof Error ? e.message : String(e);
      const transient = /\b5\d\d\b/.test(msg) || /Connection is not available|timed out|ECONNRESET|socket hang up/i.test(msg);
      if (!transient) throw e;
      console.log(`  ↻ transient on ${label} (attempt ${i + 1}/${tries}) — ${msg.slice(0, 80)}`);
      await sleep(3000 * (i + 1));
    }
  }
  throw last;
}

/** Re-establish grid + roster + presence for the current shift in the running backend instance. */
async function ensurePresence(cp: ControlPlane, m: Manifest): Promise<void> {
  const reviewerId = await cp.adminUserId();
  const hour = Number(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata', hour: '2-digit', hour12: false }));
  const shift: Shift = hour < 12 ? 'SHIFT_1' : 'SHIFT_2';
  for (const city of ['DEL', 'HYD'] as CityCode[]) {
    const daIds = m.das.filter((d) => d.city === city && d.shift === shift).map((d) => d.userId);
    await cp.replanAndApproveGrid(city, reviewerId, { date: today, shift, daIds });
  }
  await cp.shiftLoad(shift, today);
  for (const da of m.das.filter((d) => d.shift === shift)) {
    const token = (await cp.login(da.email, da.password)).token;
    await cp.daCheckIn({ ...da, token });
    await cp.daGps({ ...da, token });
  }
  console.log(`presence: ${shift} DAs present in this instance`);
}

async function buildRig(): Promise<{ rig: Rig; manifest: Manifest }> {
  const cp = new ControlPlane();
  const m = JSON.parse(await readFile(path.join(path.resolve(env.outDir), 'personas.json'), 'utf8')) as Manifest;
  const adminToken = await cp.admin();
  await ensurePresence(cp, m);

  const daByUserId = new Map<string, Persona>(m.das.map((d) => [d.userId, d]));
  const shuttle = {} as Record<CityCode, Persona>;
  for (const s of m.shuttles) shuttle[(s.city ?? (s.email.includes('del') ? 'DEL' : 'HYD')) as CityCode] = s;
  const business = {} as Rig['business'];
  for (const b of m.businesses) {
    const ownerToken = (await cp.login(b.owner.email, b.owner.password)).token;
    business[b.city] = { accountId: b.accountId, owner: b.owner, ownerToken };
  }
  return { rig: { cp, adminToken, daByUserId, shuttle, business }, manifest: m };
}

/** Drive one parcel origin→dest to DROPPED, with the DA + shuttle legs on the real app. */
async function runParcel(rig: Rig, s: Scenario, index: number): Promise<string> {
  const { cp, adminToken } = rig;
  const biz = rig.business[s.origin];
  const ownerToken = biz.ownerToken;
  const log = (m: string) => console.log(`[${s.label}] ${m}`);

  // Re-future the lane's flights (a prior parcel's departAndLand pasts the shared flight).
  await resetLaneFlightsToFuture(s.origin, s.dest);

  // 1 ── BOOK → PICKUP_ASSIGNED.
  const origin = spreadCoords(s.origin, 1, 700 + index)[0]!;
  const dest = spreadCoords(s.dest, 1, 800 + index)[0]!;
  const body = buildBooking({ b2bAccountId: biz.accountId, originCity: s.origin, destCity: s.dest, origin, dest, index, cod: s.cod });
  const ref = (await cp.book({ ...biz.owner, token: ownerToken }, body)).shipment_ref;
  log(`BOOKED ${ref}${s.cod ? ` (COD)` : ''}`);
  await cp.waitForState(ref, ownerToken, 'PICKUP_ASSIGNED');

  // 2 ── DA PICKUP (real app) → RETURNED_TO_HUB.
  const pickup = await pollTask(ref, 'PICKUP');
  const pickupDa = rig.daByUserId.get(pickup.daId);
  if (!pickupDa) throw new Error(`pickup DA ${pickup.daId} not in manifest`);
  const pickupOtp = await pollOtp(() => cp.peekPickupOtp(ref), 'pickup OTP');
  log(`pickup DA ${pickupDa.email} → driving app`);
  await daPickup({ email: pickupDa.email, password: pickupDa.password, ref, otp: pickupOtp });
  await cp.waitForState(ref, ownerToken, 'RETURNED_TO_HUB');

  // 3 ── Origin hub receive (control plane) → IN_TAKEOFF_BAG.
  await retryTransient(() => cp.api.post(`/hub/${CITY_UUID[s.origin]}/receive`, { shipment_ref: ref }, { token: adminToken }), 'origin hub receive');
  await cp.waitForState(ref, ownerToken, 'IN_TAKEOFF_BAG');

  // 4 ── Seal bag (control plane) + wait for AWB, THEN shuttle-out on the app → DISPATCHED_TO_AIRPORT.
  const bag = await pollFlightBag(ref);
  await retryTransient(() => cp.api.post(`/hub/${CITY_UUID[s.origin]}/bags/${bag.bagId}/seal`, undefined, { token: adminToken }), 'seal bag');
  await pollAwbByBag(bag.bagId, adminToken); // seal's async AWB booking settled — safe to shuttle
  log(`shuttle-out ${bag.flightNo} → driving app`);
  await shuttleOut({ email: rig.shuttle[s.origin].email, password: rig.shuttle[s.origin].password, bagId: bag.bagId });
  await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_AIRPORT');

  // 5 ── Airline AWB + GHA (control plane) → AT_AIRPORT.
  const awb = await pollAwbByBag(bag.bagId, adminToken);
  try {
    await cp.api.post(`/airline/flights/${bag.flightNo}/${bag.flightDate}/awb`, { awb_no: `AWB${Date.now()}` }, { token: adminToken });
  } catch { /* already stamped by another parcel on this flight */ }
  await retryTransient(() => cp.api.post(`/airline/awb/${awb.id}/gha-accepted`, undefined, { token: adminToken }), 'gha-accepted');
  await cp.waitForState(ref, ownerToken, 'AT_AIRPORT');

  // 6 ── Flight time-travel (control plane) → LANDED.
  await cp.departAndLand(bag.flightNo, bag.flightDate);
  await cp.waitForState(ref, ownerToken, 'LANDED');

  // 7 ── Dest shuttle collect (real app) → DISPATCHED_TO_HUB.
  log(`shuttle-collect awb ${awb.id.slice(0, 8)} → driving app`);
  await shuttleCollect({ email: rig.shuttle[s.dest].email, password: rig.shuttle[s.dest].password, awbId: awb.id });
  await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_HUB');

  // 8 ── Dest hub receive (control plane) → HUB_DELIVERY_ASSIGNED.
  await retryTransient(() => cp.api.post(`/hub/${CITY_UUID[s.dest]}/receive`, { shipment_ref: ref }, { token: adminToken }), 'dest hub receive');
  await cp.waitForState(ref, ownerToken, 'HUB_DELIVERY_ASSIGNED');

  // 9 ── DA DELIVERY. Hub-collect on the control plane first — that is what mints the delivery OTP,
  //      and the app needs the OTP value up-front (Maestro can't read it mid-flow). The app then
  //      resumes at the en-route phase (task is IN_PROGRESS) and drives the rest: scan, recipient OTP,
  //      confirm (+ COD cash) → DROPPED.
  const delivery = await pollTask(ref, 'DELIVERY');
  const deliveryDa = rig.daByUserId.get(delivery.daId);
  if (!deliveryDa) throw new Error(`delivery DA ${delivery.daId} not in manifest`);
  await retryTransient(() => cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/hub-collect`, undefined, { token: adminToken }), 'hub-collect');
  await cp.waitForState(ref, ownerToken, 'COLLECTED_FROM_HUB');
  const deliveryOtp = await pollOtp(() => cp.peekDeliveryOtp(ref), 'delivery OTP');
  log(`delivery DA ${deliveryDa.email} → driving app`);
  await daDelivery({
    email: deliveryDa.email, password: deliveryDa.password, ref, otp: deliveryOtp,
    ...(s.cod ? { codAmount: String(Math.round(s.cod / 100)) } : {}),
  });
  await cp.waitForState(ref, ownerToken, 'DROPPED');
  log(`→ DROPPED ✅`);
  return ref;
}

const SCENARIOS: Scenario[] = [
  { label: 'M1 DEL→HYD prepaid (mobile)', origin: 'DEL', dest: 'HYD' },
];

async function main() {
  const { rig } = await buildRig();
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
  console.log('\n════ mobile full-day summary ════');
  for (const r of results) console.log(`  ${r.ok ? '✅' : '❌'} ${r.label}${r.ref ? `  ${r.ref}` : ''}${r.error ? `  — ${r.error.slice(0, 90)}` : ''}`);
  const passed = results.filter((r) => r.ok).length;
  console.log(`  ${passed}/${results.length} parcels reached DROPPED through the real driver app`);
  if (passed !== results.length) process.exitCode = 1;
}

// ── helpers (mirror runDay) ──────────────────────────────────────────────────
async function pollTask(ref: string, type: string, tries = 30): Promise<{ taskId: string; daId: string }> {
  for (let i = 0; i < tries; i++) { const t = await resolveTask(ref, type); if (t) return t; await sleep(1000); }
  throw new Error(`no ${type} task for ${ref} after ${tries}s`);
}
/** Poll for an OTP that is generated asynchronously (best-effort RabbitMQ consumer) after assignment. */
async function pollOtp(fn: () => Promise<string>, label: string, tries = 60): Promise<string> {
  let last: unknown;
  for (let i = 0; i < tries; i++) {
    try { return await fn(); } catch (e) { last = e; await sleep(1500); }
  }
  throw new Error(`${label} never appeared — ${last instanceof Error ? last.message.slice(0, 80) : last}`);
}
async function pollFlightBag(ref: string, tries = 30) {
  for (let i = 0; i < tries; i++) { const b = await resolveFlightBag(ref); if (b) return b; await sleep(1000); }
  throw new Error(`no flight bag for ${ref} after ${tries}s`);
}
async function pollAwbByBag(bagId: string, token: string, tries = 40): Promise<{ id: string }> {
  const cp = new ControlPlane();
  for (let i = 0; i < tries; i++) {
    try { return await cp.api.get<{ id: string }>(`/airline/awb/by-bag/${bagId}`, { token }); }
    catch { await sleep(1500); }
  }
  throw new Error(`no AWB for bag ${bagId} after ${tries} tries`);
}

main().catch((e) => { console.error(e); process.exitCode = 1; }).finally(closeDb);
