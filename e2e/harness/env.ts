/**
 * Typed runtime config, loaded from e2e/.env (gitignored). Everything the harness talks to is an
 * env var so local ↔ Render ↔ CI are all config, never code. See .env.example.
 */
import 'dotenv/config';

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing required env var ${name} — copy e2e/.env.example to e2e/.env and fill it.`);
  return v;
}

export const env = {
  /** Backend under test. Local: http://localhost:8080. */
  backendUrl: (process.env.BACKEND_URL || 'http://localhost:8080').replace(/\/$/, ''),

  /** Postgres for the two control-plane SQL ops (credit bump, flight time-travel). */
  db: {
    url: required('E2E_DATABASE_URL'), // postgres://user:pass@host:5432/db?sslmode=require
  },

  /** Local dev ports for the six consoles (business/airline collide on 3003 → airline moved to 3005). */
  consoles: {
    customer: process.env.CUSTOMER_URL || 'http://localhost:3000',
    hub: process.env.HUB_URL || 'http://localhost:3001',
    station: process.env.STATION_URL || 'http://localhost:3002',
    business: process.env.BUSINESS_URL || 'http://localhost:3003',
    admin: process.env.ADMIN_URL || 'http://localhost:3004',
    airline: process.env.AIRLINE_URL || 'http://localhost:3005',
  },

  /** Android app id + Maestro. The emulator reaches the Mac backend at 10.0.2.2. */
  mobile: {
    appId: process.env.DRIVER_APP_ID || 'com.oneday.driver',
    maestroBin: process.env.MAESTRO_BIN || 'maestro',
  },

  /** Where run artifacts (screenshots, reports, maestro output) land. */
  outDir: process.env.E2E_OUT_DIR || 'artifacts',
} as const;
