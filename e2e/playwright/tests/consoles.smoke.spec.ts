import { test, expect } from '@playwright/test';
import { CONSOLES } from '../playwright.config';

// Smoke: each console's entry page loads, renders an interactive shell, and exposes no obvious error
// boundary. This is the scaffold layer — deepen per-console (login → dashboard → a core action) as
// stable test accounts land on staging.
for (const [name, url] of Object.entries(CONSOLES)) {
  test.describe(`${name} console`, () => {
    test(`loads and renders a shell`, async ({ page }) => {
      const resp = await page.goto(url, { waitUntil: 'domcontentloaded' });
      expect(resp?.status(), `${name} should return a non-error status`).toBeLessThan(400);

      // A real app shell, not a blank/error page.
      await expect(page.locator('body')).toBeVisible();
      await expect(page).not.toHaveTitle(/error|not found|404/i);

      // Next.js error overlay / unhandled crash boundary must not be present.
      await expect(page.getByText(/application error|something went wrong/i)).toHaveCount(0);
    });
  });
}

// Deeper flow to grow into once a seeded staging login exists (kept skipped so CI stays green):
test.skip('customer: login → home', async ({ page }) => {
  await page.goto(CONSOLES.customer);
  await page.getByLabel(/email/i).fill(process.env.E2E_CUSTOMER_EMAIL || '');
  await page.getByLabel(/password/i).fill(process.env.E2E_CUSTOMER_PASSWORD || '');
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await expect(page).toHaveURL(/home|dashboard/i);
});
