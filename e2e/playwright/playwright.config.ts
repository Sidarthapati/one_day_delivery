import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// E2E against the SIX consoles run LOCALLY (pnpm dev) pointed at the local backend on :8080 — the
// isolated e2e stack, NOT the deployed Vercel consoles. Every URL is env-overridable, so the same
// specs run against a preview/prod domain (or the Vercel aliases) by exporting BUSINESS_URL etc.
// Ports mirror e2e/harness/env.ts (business & airline both default to 3003 → airline moved to 3005).
const ARTIFACTS = path.resolve(__dirname, '..', 'artifacts', 'playwright');

export default defineConfig({
  testDir: './tests',
  outputDir: path.join(ARTIFACTS, 'test-results'),
  // The full-day pipeline spec drives 4 consoles + the control plane + flight time-travel end to
  // end, so it needs a generous ceiling; the smoke specs finish in seconds.
  timeout: 10 * 60_000,
  expect: { timeout: 20_000 },
  // The pipeline is one ordered story across consoles — never run its steps in parallel, and don't
  // retry (a retry would re-book and desync backend state). Smoke tests are independent.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: path.join(ARTIFACTS, 'report') }],
  ],
  use: {
    // Trace + video + screenshots retained so the handoff has a scrubbable record of every run,
    // not just failures. Milestone screenshots are also captured explicitly by the pipeline spec.
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 30_000,
    navigationTimeout: 45_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});

// Console URLs — env override wins; defaults are the local dev servers (see e2e/README.md → "Bring
// up the consoles"). To point the suite at the deployed Vercel consoles, export the *_URL vars.
export const CONSOLES = {
  customer: process.env.CUSTOMER_URL || 'http://localhost:3000',
  hub: process.env.HUB_URL || 'http://localhost:3001',
  station: process.env.STATION_URL || 'http://localhost:3002',
  business: process.env.BUSINESS_URL || 'http://localhost:3003',
  admin: process.env.ADMIN_URL || 'http://localhost:3004',
  airline: process.env.AIRLINE_URL || 'http://localhost:3005',
} satisfies Record<string, string>;

export const ARTIFACT_DIR = ARTIFACTS;
