import { test, expect, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import {
  ControlPlane, closeDb, resolveTask, resolveFlightBag, resetLaneFlightsToFuture, sleep,
  type CityCode, type Persona,
} from '../../harness/index';
import { ARTIFACT_DIR } from '../playwright.config';
import { LoginPage } from '../pom/LoginPage';
import { BusinessShipPage } from '../pom/BusinessShipPage';
import { HubConsole } from '../pom/HubConsole';
import { AirlineConsole } from '../pom/AirlineConsole';

/**
 * Full-day intercity HUB_RETURN pipeline, driven through the REAL web consoles wherever a screen
 * exists, and through the control plane only for the screenless personas (DA + shuttle mobile, which
 * Maestro will take over) and the clock (flight time-travel). Every web action is a genuine
 * click/type in a locally-served console pointed at the local backend; between surfaces we gate on
 * the customer track (the one endpoint ADMIN can't hit → the booking owner's own token).
 *
 * Web surface proven here: business `ship` (book) · hub receive ×2 · hub flight-bag seal · airline
 * AWB + GHA · hub delivery-bag seal. Prereq: `npm run seed` + all consoles up (see e2e/README.md).
 */

interface Manifest {
  businesses: { city: CityCode; accountId: string; owner: Persona }[];
  hubOps: Persona[];
  gha: Persona;
  shuttles: Persona[];
}

interface Rig {
  cp: ControlPlane;
  adminToken: string;
  ownerToken: Record<string, string>; // by owner email
  hubOp: { email: string; password: string };
  gha: { email: string; password: string };
  shuttle: Record<CityCode, Persona>; // logged-in, with token
  businesses: Manifest['businesses'];
}

interface Scenario {
  label: string;
  origin: CityCode;
  dest: CityCode;
  cod?: number; // paise
}

const SCENARIOS: Scenario[] = [
  { label: 'A1 DEL to HYD prepaid', origin: 'DEL', dest: 'HYD' },
  { label: 'A2 HYD to DEL prepaid', origin: 'HYD', dest: 'DEL' },
  { label: 'B1 DEL to HYD COD', origin: 'DEL', dest: 'HYD', cod: 150_000 },
];

const MILESTONES = path.join(ARTIFACT_DIR, 'milestones');
mkdirSync(MILESTONES, { recursive: true });

let rig: Rig;

test.beforeAll(async () => {
  const raw = await readFile(path.join(path.resolve('artifacts'), 'personas.json'), 'utf8');
  const m = JSON.parse(raw) as Manifest;
  const cp = new ControlPlane();
  const adminToken = await cp.admin();

  const ownerToken: Record<string, string> = {};
  for (const b of m.businesses) ownerToken[b.owner.email] = (await cp.login(b.owner.email, b.owner.password)).token;

  const shuttle = {} as Record<CityCode, Persona>;
  for (const s of m.shuttles) {
    const city = (s.city ?? (s.email.includes('del') ? 'DEL' : 'HYD')) as CityCode;
    shuttle[city] = { ...s, city, token: (await cp.login(s.email, s.password)).token };
  }

  rig = {
    cp, adminToken, ownerToken,
    // Hub ops aren't city-scoped in the backend yet, so one login drives both hubs via the picker.
    hubOp: { email: m.hubOps[0]!.email, password: 'HubOps@2026' },
    gha: { email: m.gha.email, password: 'GhaOps@2026' },
    shuttle, businesses: m.businesses,
  };
});

test.afterAll(async () => {
  await closeDb();
});

for (const s of SCENARIOS) {
  test(s.label, async ({ page }) => {
    const { cp, adminToken } = rig;
    const biz = rig.businesses.find((b) => b.city === s.origin)!;
    const ownerToken = rig.ownerToken[biz.owner.email]!;
    const shot = (n: string) => page.screenshot({ path: path.join(MILESTONES, `${slug(s.label)}-${n}.png`), fullPage: true });

    // Re-future this lane's flights first: a prior scenario's departAndLand lands (pasts) the shared
    // flight, and the cron-meeting constraint won't assign a booking with no future flight left.
    await resetLaneFlightsToFuture(s.origin, s.dest);

    // ── 1. BOOK (business console) → BOOKED → PICKUP_ASSIGNED ──────────────────
    await new LoginPage(page, 'business').signIn(biz.owner.email, biz.owner.password);
    const ship = new BusinessShipPage(page);
    await ship.goto();
    const ref = await ship.book({ senderCity: s.origin, receiverCity: s.dest, cod: s.cod });
    await shot('01-booked');
    test.info().annotations.push({ type: 'shipment_ref', description: ref });
    await cp.waitForState(ref, ownerToken, 'PICKUP_ASSIGNED');

    // ── 2–4. DA pickup + hub handoff (mobile → control plane) ──────────────────
    const pickup = await pollTask(ref, 'PICKUP');
    await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/en-route`, undefined, { token: adminToken });
    await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/arrived`, undefined, { token: adminToken });
    await cp.api.post(`/internal/v1/shipments/${ref}/pickup-otp/verify`, { otp: await cp.peekPickupOtp(ref) }, { token: adminToken });
    await cp.waitForState(ref, ownerToken, 'PICKED_UP');
    await cp.api.post(`/dispatch/da/${pickup.daId}/tasks/${pickup.taskId}/hub-handoff`, { parcel_scans: [ref] }, { token: adminToken });
    await cp.waitForState(ref, ownerToken, 'RETURNED_TO_HUB');

    // ── 5. Origin hub RECEIVE (hub console) → IN_TAKEOFF_BAG ───────────────────
    const hub = new HubConsole(page);
    await new LoginPage(page, 'hub').signIn(rig.hubOp.email, rig.hubOp.password);
    await hub.selectHub(s.origin);
    await hub.receive(ref);
    await shot('05-origin-hub-received');
    await cp.waitForState(ref, ownerToken, 'IN_TAKEOFF_BAG');

    // ── 6–8. Seal flight bag (hub) → shuttle to airport → DISPATCHED_TO_AIRPORT ─
    const bag = await pollFlightBag(ref);
    await hub.sealFlightBag(bag.bagId);
    await shot('06-flight-bag-sealed');
    // Sealing books the AWB asynchronously off BAG_SEALED. Wait for the AWB to exist BEFORE the
    // shuttle leaves: it's the signal that the seal's async processing has fully committed (dispatching
    // in the ~100ms gap races that processing and transiently poisons the shuttle transaction), and it
    // mirrors reality — a shuttle only runs a bag to the airport once its air waybill is booked.
    const awb = await pollAwbByBag(bag.bagId, adminToken);
    await cp.api.post(`/shuttle/${rig.shuttle[s.origin].userId}/out-to-airport`, { bag_ids: [bag.bagId] }, { token: rig.shuttle[s.origin].token });
    await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_AIRPORT');

    // ── 9–10. AWB + GHA (airline console) → AT_AIRPORT ─────────────────────────
    const airline = new AirlineConsole(page);
    await new LoginPage(page, 'airline').signIn(rig.gha.email, rig.gha.password);
    await airline.open(bag.bagId);
    await airline.stampAwbIfNeeded();
    await airline.ghaAccepted();
    await shot('09-airline-gha-accepted');
    await cp.waitForState(ref, ownerToken, 'AT_AIRPORT');

    // ── 11. Flight time-travel (clock → control plane) → LANDED ────────────────
    await cp.departAndLand(bag.flightNo, bag.flightDate);
    await cp.waitForState(ref, ownerToken, 'LANDED');

    // ── 12. Dest shuttle collect (mobile → control plane) → DISPATCHED_TO_HUB ───
    await cp.api.post(`/shuttle/${rig.shuttle[s.dest].userId}/awbs/${awb.id}/collect-from-airport`, undefined, { token: rig.shuttle[s.dest].token });
    await cp.waitForState(ref, ownerToken, 'DISPATCHED_TO_HUB');

    // ── 13–14. Dest hub receive + delivery-bag seal (hub) → HUB_DELIVERY_ASSIGNED
    await hub.selectHub(s.dest);
    await hub.receive(ref);
    await shot('13-dest-hub-received');
    await hub.sealOpenDeliveryBags();
    await shot('14-delivery-bag-sealed');
    await cp.waitForState(ref, ownerToken, 'HUB_DELIVERY_ASSIGNED');

    // ── 15. Dest DA hub-collect + delivery OTP (mobile → control plane) → DROPPED
    const delivery = await pollTask(ref, 'DELIVERY');
    await cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/hub-collect`, undefined, { token: adminToken });
    await cp.waitForState(ref, ownerToken, 'COLLECTED_FROM_HUB');
    await cp.api.post(`/internal/v1/shipments/${ref}/delivery-otp/verify`, { otp: await cp.peekDeliveryOtp(ref) }, { token: adminToken });
    await cp.api.post(`/dispatch/da/${delivery.daId}/tasks/${delivery.taskId}/drop-completed`, { cod_collected: !!s.cod }, { token: adminToken });
    await cp.waitForState(ref, ownerToken, 'DROPPED');

    const finalTrack = await cp.track(ref, ownerToken);
    expect(finalTrack.state).toBe('DROPPED');
    console.log(`[${s.label}] ${ref} → DROPPED ✅`);
  });
}

// ── polling helpers (mirror the control-plane runner) ──────────────────────────
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
      await sleep(1500);
    }
  }
  throw new Error(`no AWB for bag ${bagId} after ${tries} tries`);
}

function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
}
