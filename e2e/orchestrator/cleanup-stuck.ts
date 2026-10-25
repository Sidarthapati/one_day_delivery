/**
 * TEMP hygiene: cancel pre-pickup test parcels (BOOKED / PICKUP_ASSIGNED) left on the shared e2e DB
 * by earlier runs — they clutter a DA's route so the app's "next stop" no longer opens the current
 * parcel. Cancels through the real API (which drops the DA task). Run from e2e/: npx tsx cleanup-stuck.ts
 */
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { ControlPlane, closeDb, env, query, resolveTask, sleep, type CityCode, type Persona } from '../harness/index';

interface Manifest { businesses: { city: CityCode; accountId: string; owner: Persona }[] }

async function main() {
  const cp = new ControlPlane();
  const adminToken = await cp.admin();
  const m = JSON.parse(await readFile(path.join(path.resolve(env.outDir), 'personas.json'), 'utf8')) as Manifest;

  const rows = await query<{ shipment_ref: string; state: string; b2b_account_id: string | null }>(
    `SELECT shipment_ref, state, b2b_account_id FROM shipments WHERE state IN ('BOOKED','PICKUP_ASSIGNED') ORDER BY created_at`,
  );
  console.log(`found ${rows.length} pre-pickup parcel(s) to clear`);

  const tokenByAccount = new Map<string, { token: string }>();
  for (const b of m.businesses) tokenByAccount.set(b.accountId, { token: (await cp.login(b.owner.email, b.owner.password)).token });

  let cancelled = 0;
  for (const r of rows) {
    const owner = r.b2b_account_id ? tokenByAccount.get(r.b2b_account_id) : undefined;
    if (!owner) { console.log(`  skip ${r.shipment_ref} (${r.state}) — no owner token`); continue; }
    try {
      await cp.api.del(`/api/v1/b2b/shipments/${r.shipment_ref}?reason=e2e-cleanup`, { token: owner.token });
      console.log(`  cancelled ${r.shipment_ref} (${r.state})`);
      cancelled++;
    } catch (e) {
      console.log(`  could not cancel ${r.shipment_ref} (${r.state}) — ${e instanceof Error ? e.message.slice(0, 70) : e}`);
    }
  }
  console.log(`✅ cancelled ${cancelled}/${rows.length}`);

  // Drain delivery-stage parcels to DROPPED (they clutter the dest DA's route so the app's next-stop
  // no longer opens the current parcel). Drive via the control plane: hub-collect (if needed) → OTP → drop.
  const stuck = await query<{ shipment_ref: string; state: string }>(
    `SELECT shipment_ref, state FROM shipments WHERE state IN ('HUB_DELIVERY_ASSIGNED','COLLECTED_FROM_HUB') ORDER BY created_at`,
  );
  console.log(`draining ${stuck.length} delivery-stage parcel(s)`);
  for (const r of stuck) {
    try {
      const task = await resolveTask(r.shipment_ref, 'DELIVERY');
      if (!task) { console.log(`  skip ${r.shipment_ref} — no delivery task`); continue; }
      if (r.state === 'HUB_DELIVERY_ASSIGNED') {
        await cp.api.post(`/dispatch/da/${task.daId}/tasks/${task.taskId}/hub-collect`, undefined, { token: adminToken });
      }
      let otp = '';
      for (let i = 0; i < 40 && !otp; i++) { try { otp = await cp.peekDeliveryOtp(r.shipment_ref); } catch { await sleep(1500); } }
      if (!otp) { console.log(`  skip ${r.shipment_ref} — no delivery OTP`); continue; }
      await cp.api.post(`/internal/v1/shipments/${r.shipment_ref}/delivery-otp/verify`, { otp }, { token: adminToken });
      await cp.api.post(`/dispatch/da/${task.daId}/tasks/${task.taskId}/drop-completed`, { cod_collected: false }, { token: adminToken });
      console.log(`  drained ${r.shipment_ref} → DROPPED`);
    } catch (e) {
      console.log(`  could not drain ${r.shipment_ref} — ${e instanceof Error ? e.message.slice(0, 70) : e}`);
    }
  }
}

main().catch((e) => { console.error(e); process.exitCode = 1; }).finally(closeDb);
