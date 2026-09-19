/**
 * One-time control-plane seed for a full-day run. Idempotent-ish per fresh DB. Writes a persona
 * manifest to artifacts/personas.json for the Playwright/Maestro layers to consume.
 *
 * Requires the backend + oneday-db-e2e + broker + consolidator up (see e2e/README.md). The B2B
 * account step is still pending live wiring (ControlPlane.provisionB2bAccount) — see plan Phase 1.
 */
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ControlPlane, closeDb, env, type CityCode, type Persona, type Shift } from '../harness/index';

const CITIES: CityCode[] = ['DEL', 'HYD'];
const DAS_PER_CITY_SHIFT = 4;
const DA_PW = 'DaTest@2026';
// The backend operates in IST (Asia/Kolkata) — grid plan valid_date, shift roster, attendance, and
// the shipment ref date are all IST. Using the UTC date breaks bookings in the 18:30–24:00 UTC window
// (00:00–05:30 IST next day), when UTC and IST dates differ. en-CA gives ISO YYYY-MM-DD.
const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

async function main() {
  const cp = new ControlPlane();
  const reviewerId = await cp.adminUserId();
  console.log(`admin ok (reviewer ${reviewerId.slice(0, 8)})`);

  // 1. Both cities → HUB_RETURN.
  for (const c of CITIES) await cp.setHubReturn(c);
  console.log('fleet: DEL + HYD set to HUB_RETURN');

  // 2. Register DAs (both shifts) + ops personas.
  const das: (Persona & { shift: Shift })[] = [];
  for (const city of CITIES) {
    for (const shift of ['SHIFT_1', 'SHIFT_2'] as Shift[]) {
      for (let i = 0; i < DAS_PER_CITY_SHIFT; i++) {
        const email = `da.${city.toLowerCase()}.${shift.toLowerCase()}.${i}@e2e.local`;
        const p = await cp.registerDa({
          firstName: 'DA',
          lastName: `${city}-${shift}-${i}`,
          email,
          city,
          shift,
          contractStart: today,
          password: DA_PW,
        });
        const login = await cp.login(email, DA_PW);
        das.push({ ...p, token: login.token, shift });
      }
    }
  }
  const hubOps: Persona[] = [];
  for (const city of CITIES) {
    hubOps.push(await cp.registerUser({
      name: `Hub ${city}`, email: `hub.${city.toLowerCase()}@e2e.local`, password: 'HubOps@2026',
      role: 'HUB_OPERATOR', cityId: city,
    }));
  }
  const station = await cp.registerUser({
    name: 'Station', email: 'station@e2e.local', password: 'Station@2026', role: 'STATION_MANAGER', cityId: 'DEL',
  });
  const gha = await cp.registerUser({
    name: 'GHA', email: 'gha@e2e.local', password: 'GhaOps@2026', role: 'AIRLINE_GHA', cityId: 'DEL',
  });
  const shuttles: Persona[] = [];
  for (const city of CITIES) {
    shuttles.push(await cp.registerUser({
      name: `Shuttle ${city}`, email: `shuttle.${city.toLowerCase()}@e2e.local`, password: 'Shuttle@2026',
      role: 'SHUTTLE_AGENT', cityId: city, // IATA code required for shuttle
    }));
  }
  console.log(`personas: ${das.length} DAs, ${hubOps.length} hub ops, station, gha, ${shuttles.length} shuttle agents`);

  // 3. Grid hex plan for TODAY + SHIFT_1 (required for pickup auto-assignment + shift-load).
  //    Must pass the shift's DA ids explicitly — see ControlPlane.replanAndApproveGrid.
  for (const city of CITIES) {
    const shift1DaIds = das.filter((d) => d.city === city && d.shift === 'SHIFT_1').map((d) => d.userId);
    await cp.replanAndApproveGrid(city, reviewerId, { date: today, shift: 'SHIFT_1', daIds: shift1DaIds });
  }
  console.log('grid: replanned + approved DEL + HYD (today, SHIFT_1)');

  // 4. Load SHIFT_1 roster and bring its DAs present.
  await cp.shiftLoad('SHIFT_1', today);
  for (const da of das.filter((d) => d.shift === 'SHIFT_1')) {
    await cp.daCheckIn(da);
    await cp.daGps(da);
  }
  console.log('shift: SHIFT_1 loaded, DAs checked in');

  // 5. Provision 2 B2B accounts (DEL + HYD owners) — onboarding auto-approves (pan = gstin[2..12]),
  //    then credit + rate card are set so CREDIT bookings price + pass. Captured for booking.
  const bizSpec = [
    { city: 'DEL' as CityCode, gstin: '22ABCDE1234F1Z5', pan: 'ABCDE1234F', phone: '+919000000001' },
    { city: 'HYD' as CityCode, gstin: '36FGHIJ5678K1Z8', pan: 'FGHIJ5678K', phone: '+919000000002' },
  ];
  const businesses: { city: CityCode; accountId: string; owner: Persona }[] = [];
  for (const b of bizSpec) {
    const email = `biz.${b.city.toLowerCase()}@e2e.local`;
    const { accountId, owner } = await cp.provisionB2bAccount({
      companyName: `${b.city} Traders`, ownerName: `${b.city} Owner`, ownerEmail: email,
      ownerPassword: 'BizOwner@2026', phone: b.phone, gstin: b.gstin, pan: b.pan,
      billingEmail: email, homeCity: b.city, creditLimitPaise: 100_000_000,
    });
    businesses.push({ city: b.city, accountId, owner });
  }
  console.log(`businesses: ${businesses.length} B2B accounts provisioned (DEL + HYD)`);

  const out = path.resolve(env.outDir);
  await mkdir(out, { recursive: true });
  await writeFile(
    path.join(out, 'personas.json'),
    JSON.stringify({ das, hubOps, station, gha, shuttles, businesses }, null, 2),
  );
  console.log(`manifest → ${path.join(out, 'personas.json')}`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(closeDb);
