/**
 * TEMP: re-establish grid plan + roster + DA presence for the CURRENT shift in the running backend
 * (presence is in-memory and lost on a backend relaunch). Re-approves the grid, re-loads the shift,
 * re-checks-in + GPS-pings the current shift's DAs. Run from e2e/: npx tsx refresh-presence.ts
 */
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { ControlPlane, closeDb, env, query, type CityCode, type Persona, type Shift } from '../harness/index';
import { CITY_SLUG } from '../harness/constants';

const CITIES: CityCode[] = ['DEL', 'HYD'];
const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

async function main() {
  const cp = new ControlPlane();
  const reviewerId = await cp.adminUserId();
  const m = JSON.parse(await readFile(path.join(path.resolve(env.outDir), 'personas.json'), 'utf8')) as {
    das: (Persona & { shift: Shift })[];
  };

  const hour = Number(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata', hour: '2-digit', hour12: false }));
  const shift: Shift = hour < 12 ? 'SHIFT_1' : 'SHIFT_2';
  console.log(`IST hour ${hour} → current shift ${shift}`);

  // Clear orphaned non-APPROVED assignments for today (a partial replan across the midnight boundary
  // leaves PROPOSED rows that collide on the unique key when replan re-inserts).
  await query(`DELETE FROM da_hex_assignment WHERE valid_date = $1 AND status <> 'APPROVED'`, [today]);
  console.log(`cleared stale PROPOSED assignments for ${today}`);

  for (const city of CITIES) {
    const daIds = m.das.filter((d) => d.city === city && d.shift === shift).map((d) => d.userId);
    await cp.replanAndApproveGrid(city, reviewerId, { date: today, shift, daIds });
    console.log(`grid re-approved: ${city} ${shift} (${daIds.length} DAs)`);
  }
  await cp.shiftLoad(shift, today);
  console.log(`shift-load: ${shift} ${today}`);

  let present = 0;
  for (const da of m.das.filter((d) => d.shift === shift)) {
    const token = (await cp.login(da.email, da.password)).token;
    const fresh = { ...da, token, cityId: (da as any).cityId ?? CITY_SLUG[da.city as CityCode] };
    try {
      await cp.daCheckIn(fresh);
      await cp.daGps(fresh);
      present++;
    } catch (e) {
      console.log(`  check-in rejected for ${da.email} — ${e instanceof Error ? e.message.slice(0, 70) : e}`);
    }
  }
  console.log(`  ${present} present`);
  console.log(`✅ presence refreshed for ${shift}`);
}

main().catch((e) => { console.error(e); process.exitCode = 1; }).finally(closeDb);
