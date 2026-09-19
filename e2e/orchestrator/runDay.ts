/**
 * Full-day A1 spine: drive one B2B DEL→HYD parcel through the entire intercity HUB_RETURN chain
 * to DROPPED, gating on the customer track between surfaces. Every transition is a real backend
 * call. Per the verified endpoint map ADMIN can drive all hub/airline/shuttle/DA/OTP endpoints, so
 * the driver authenticates as ADMIN and gates on the owner's own track token (the one endpoint
 * ADMIN cannot hit). This is the proof the rig works end-to-end; Tiers A–D layer on top.
 *
 * Prereq: `npm run seed` (writes artifacts/personas.json + a live SHIFT_1 roster). Backend up.
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

async function loadManifest(): Promise<Manifest> {
  const raw = await readFile(path.join(path.resolve(env.outDir), 'personas.json'), 'utf8');
  return JSON.parse(raw) as Manifest;
}

async function main() {
  const cp = new ControlPlane();
  const manifest = await loadManifest();
  const del = manifest.businesses.find((b) => b.city === 'DEL');
  if (!del) throw new Error('no DEL business in personas.json — run `npm run seed` first');

  // Fresh owner token (seed-time tokens may have expired) — used only for track gating.
  const ownerToken = (await cp.login(del.owner.email, del.owner.password)).token;
  const adminToken = await cp.admin();

  // Shuttle endpoints resolve the agent's city from its JWT (ADMIN has none → dispatches nothing),
  // so drive them as the real DEL/HYD shuttle agents with fresh tokens. shuttles = [DEL, HYD].
  const delShuttle = manifest.shuttles[0]!;
  const hydShuttle = manifest.shuttles[1]!;
  const delShuttleToken = (await cp.login(delShuttle.email, delShuttle.password)).token;
  const hydShuttleToken = (await cp.login(hydShuttle.email, hydShuttle.password)).token;

  const origin = spreadCoords('DEL', 1, 101)[0]!;
  const dest = spreadCoords('HYD', 1, 202)[0]!;

  // 1 ── BOOK (as the B2B owner) → BOOKED, then auto PICKUP_ASSIGNED.
  const body = buildBooking({ b2bAccountId: del.accountId, originCity: 'DEL', destCity: 'HYD', origin, dest, index: 1 });
  const booking = await cp.book({ ...del.owner, token: ownerToken }, body);
  const ref = booking.shipment_ref;
  log(`BOOKED  ${ref}`);
  await cp.waitForState(ref, ownerToken, 'PICKUP_ASSIGNED');
  log('→ PICKUP_ASSIGNED (auto M5 assignment)');

  // 2 ── PICKUP DA: resolve the auto-assigned task, en-route/arrived, verify pickup OTP → PICKED_UP.
  const pickup = await pollTask(ref, 'PICKUP');
  await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/en-route`, undefined, { token: adminToken });
  await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/arrived`, undefined, { token: adminToken });
  const pickupOtp = await cp.peekPickupOtp(ref);
  await cp.api.post(`/internal/v1/shipments/${ref}/pickup-otp/verify`, { otp: pickupOtp }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'PICKED_UP');
  log('→ PICKED_UP (pickup OTP verified)');

  // 3 ── DA hands off at origin hub (HUB_RETURN, no van) → RETURNED_TO_HUB.
  await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/hub-handoff`, { parcel_scans: [ref] }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'RETURNED_TO_HUB');
  log('→ RETURNED_TO_HUB (hub handoff)');

  // 4 ── Origin hub receive → AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING → IN_TAKEOFF_BAG (sort ladder).
  await cp.api.post(`/hub/${CITY_UUID.DEL}/receive`, { shipment_ref: ref }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'IN_TAKEOFF_BAG');
  log('→ IN_TAKEOFF_BAG (origin hub receive + sort)');

  // 5 ── Seal the flight bag (→ BAG_SEALED → AWB booked via RabbitMQ). Then shuttle to airport.
  const bag = await pollFlightBag(ref);
  await cp.api.post(`/hub/${CITY_UUID.DEL}/bags/${bag.bagId}/seal`, undefined, { token: adminToken });
  log(`sealed flight bag ${bag.bagId.slice(0, 8)} (flight ${bag.flightNo} ${bag.flightDate})`);
  await cp.api.post(`/shuttle/${delShuttle.userId}/out-to-airport`, { bag_ids: [bag.bagId] }, { token: delShuttleToken });
  await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_AIRPORT');
  log('→ DISPATCHED_TO_AIRPORT (shuttle out)');

  // 6 ── Airline: AWB (booked async on seal) → GHA accepted → AT_AIRPORT.
  const awb = await pollAwbByBag(bag.bagId, adminToken);
  // Stamp a human AWB number (cosmetic) — best-effort: the flight may already be stamped by an
  // earlier parcel on the same flight (409). GHA-accept keys off awbId, not the stamped number.
  try {
    await cp.api.post(`/airline/flights/${bag.flightNo}/${bag.flightDate}/awb`, { awb_no: `AWB${Date.now()}` }, { token: adminToken });
  } catch { /* already stamped for this flight — fine */ }
  await cp.api.post(`/airline/awb/${awb.id}/gha-accepted`, undefined, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'AT_AIRPORT');
  log(`→ AT_AIRPORT (AWB ${awb.id.slice(0, 8)} GHA-accepted)`);

  // 7 ── Flight time-travel → DEPARTED → LANDED.
  await cp.departAndLand(bag.flightNo, bag.flightDate);
  await cp.waitForState(ref, ownerToken, 'LANDED');
  log('→ LANDED (flight time-travel + force-poll)');

  // 8 ── Dest shuttle collects from airport → DISPATCHED_TO_HUB.
  await cp.api.post(`/shuttle/${hydShuttle.userId}/awbs/${awb.id}/collect-from-airport`, undefined, { token: hydShuttleToken });
  await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_HUB');
  log('→ DISPATCHED_TO_HUB (dest shuttle collect)');

  // 9 ── Dest hub receive/break → AT_DEST_HUB → DEST_HUB_PROCESSING → HUB_DELIVERY_ASSIGNED (auto).
  await cp.api.post(`/hub/${CITY_UUID.HYD}/receive`, { shipment_ref: ref }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'HUB_DELIVERY_ASSIGNED');
  log('→ HUB_DELIVERY_ASSIGNED (dest hub sort → HUB_RETURN delivery assign)');

  // 10 ── Dest DA collects from hub → COLLECTED_FROM_HUB, then delivery OTP → DROPPED.
  const delivery = await pollTask(ref, 'DELIVERY');
  await cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/hub-collect`, undefined, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'COLLECTED_FROM_HUB');
  log('→ COLLECTED_FROM_HUB (dest DA hub-collect)');

  // (en-route/arrived are PICKUP-only; a DELIVERY task goes straight from hub-collect to OTP + drop.)
  const deliveryOtp = await cp.peekDeliveryOtp(ref);
  await cp.api.post(`/internal/v1/shipments/${ref}/delivery-otp/verify`, { otp: deliveryOtp }, { token: adminToken });
  await cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/drop-completed`, { cod_collected: false }, { token: adminToken });
  await cp.waitForState(ref, ownerToken, 'DROPPED');
  log('→ DROPPED ✅  A1 spine complete');
}

// ── helpers ──────────────────────────────────────────────────────────────
function log(msg: string) {
  console.log(`[runDay] ${msg}`);
}

/** Task rows land a beat after the state event — poll the dispatch queue until the task appears. */
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
      await sleep(1500); // AWB is booked async off the BAG_SEALED RabbitMQ event.
    }
  }
  throw new Error(`no AWB for bag ${bagId} after ${tries} tries (RabbitMQ BAG_SEALED consumer?)`);
}

main()
  .then(() => log('done'))
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(closeDb);
