import { defineConfig, devices } from '@playwright/test';

// Smoke E2E against the six deployed Vercel consoles (stable *.vercel.app aliases). Override any URL
// via env (e.g. CUSTOMER_URL) to point at a preview/prod domain.
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});

// Console URLs — env override wins; defaults are the stable Vercel aliases.
export const CONSOLES: Record<string, string> = {
  customer: process.env.CUSTOMER_URL || 'https://godspeed-customer.vercel.app',
  business: process.env.BUSINESS_URL || 'https://godspeed-business.vercel.app',
  hub: process.env.HUB_URL || 'https://godspeed-hub.vercel.app',
  station: process.env.STATION_URL || 'https://godspeed-station.vercel.app',
  airline: process.env.AIRLINE_URL || 'https://godspeed-airline.vercel.app',
  admin: process.env.ADMIN_URL || 'https://godspeed-admin.vercel.app',
};
