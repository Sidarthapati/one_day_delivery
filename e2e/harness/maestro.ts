/**
 * Maestro runner — shells out to the Maestro CLI to drive the mobile personas (DA pickup/delivery,
 * shuttle) through the real driver app on an Android emulator. The orchestrator composes these with
 * control-plane state-gating (peek the OTP, wait for the target state) exactly like the web spec does.
 *
 * Prereq: emulator up + the driver app installed as a dev client built with EXPO_PUBLIC_MOCK_SCANNER
 * + EXPO_PUBLIC_ALLOW_EMULATOR (see maestro/README.md). Maestro bin + app id come from env.
 */
import { spawn } from 'node:child_process';
import { mkdirSync, createWriteStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { env } from './env';

const MAESTRO_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'maestro');

export interface MaestroResult {
  ok: boolean;
  code: number | null;
  flow: string;
  logPath: string;
}

/** Run one flow with `--env K=V` pairs; tees output to artifacts/maestro/<flow>-<ts>.log. */
export function runMaestro(flow: string, vars: Record<string, string> = {}): Promise<MaestroResult> {
  const logDir = path.resolve(env.outDir, 'maestro');
  mkdirSync(logDir, { recursive: true });
  const base = flow.replace(/[^a-z0-9]+/gi, '-').replace(/(^-|-$)/g, '');
  const logPath = path.join(logDir, `${base}-${Date.now()}.log`);
  const flowPath = path.join(MAESTRO_DIR, flow);

  const args = ['test', flowPath];
  for (const [k, v] of Object.entries(vars)) args.push('--env', `${k}=${v}`);

  return new Promise((resolve, reject) => {
    const out = createWriteStream(logPath);
    // `maestro` inspects the connected device via adb; env.mobile.maestroBin lets CI point at ~/.maestro/bin/maestro.
    const child = spawn(env.mobile.maestroBin, args, { env: process.env });
    child.stdout.pipe(out);
    child.stderr.pipe(out);
    child.stdout.on('data', (d) => process.stdout.write(d));
    child.stderr.on('data', (d) => process.stderr.write(d));
    child.on('error', reject);
    child.on('close', (code) => {
      out.end();
      resolve({ ok: code === 0, code, flow, logPath });
    });
  });
}

/** Assert-style wrapper: throw with the log path if the flow didn't pass. */
export async function expectMaestro(flow: string, vars: Record<string, string> = {}): Promise<void> {
  const r = await runMaestro(flow, vars);
  if (!r.ok) throw new Error(`Maestro flow ${flow} failed (exit ${r.code}) — see ${r.logPath}`);
}

// ── Typed flows (the orchestrator supplies the live ref/otp/flight per scenario) ──
export const daPickup = (v: { email: string; password: string; ref: string; otp: string }) =>
  expectMaestro('da-pickup.yaml', { EMAIL: v.email, PASSWORD: v.password, PARCEL_REF: v.ref, OTP: v.otp });

export const daDelivery = (v: { email: string; password: string; ref: string; otp: string; codAmount?: string }) =>
  expectMaestro('da-delivery.yaml', {
    EMAIL: v.email, PASSWORD: v.password, PARCEL_REF: v.ref, OTP: v.otp,
    // Always supplied so the (skipped) COD block never references an unresolved var; only used on the COD path.
    COD_AMOUNT: v.codAmount ?? '0',
  });

export const shuttleOut = (v: { email: string; password: string; bagId: string }) =>
  expectMaestro('shuttle-out.yaml', { EMAIL: v.email, PASSWORD: v.password, BAG_ID: v.bagId });

export const shuttleCollect = (v: { email: string; password: string; awbId: string }) =>
  expectMaestro('shuttle-collect.yaml', { EMAIL: v.email, PASSWORD: v.password, AWB_ID: v.awbId });
