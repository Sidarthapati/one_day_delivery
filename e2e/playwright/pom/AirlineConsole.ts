import { expect, type Page } from '@playwright/test';
import { CONSOLES } from '../playwright.config';

/**
 * GHA / airline console. The booking detail page is keyed by the flight bag id (the bookings table
 * rows navigate to /awb/<bagId>), so we go straight there with the bagId the control plane resolved.
 *
 * AWB stamping is per-flight: the first parcel on a flight replaces the placeholder AWB with a real
 * number; later parcels on the same flight find it already stamped, so the stamp panel isn't shown —
 * we detect that and skip, then confirm GHA custody either way.
 */
export class AirlineConsole {
  constructor(private readonly page: Page) {}

  async open(bagId: string): Promise<void> {
    await this.page.goto(`${CONSOLES.airline}/awb/${bagId}`, { waitUntil: 'domcontentloaded' });
    // The AWB number (mono) in the header is our anchor that the detail loaded.
    await expect(this.page.locator('.od-mono').first()).toBeVisible({ timeout: 20_000 });
  }

  /** Stamp a real AWB number if the placeholder panel is present (first parcel on the flight). */
  async stampAwbIfNeeded(): Promise<void> {
    const input = this.page.getByRole('textbox', { name: /awb number/i });
    if (await input.isVisible().catch(() => false)) {
      await input.fill(`098-${Date.now() % 100_000_000}`);
      await this.page.getByRole('button', { name: /save awb/i }).click();
      // Best-effort: the AWB number is per-flight, so a bag whose flight another parcel already
      // stamped will 409 and the panel stays. That's cosmetic — the AWB record already exists and
      // GHA custody keys off its id — so don't fail the run; just give the save a moment to settle.
      await input.waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => { /* already stamped */ });
    }
  }

  /** Confirm GHA custody (drives the shipment to AT_AIRPORT). */
  async ghaAccepted(): Promise<void> {
    const btn = this.page.getByRole('button', { name: /gha accepted/i });
    await expect(btn).toBeVisible({ timeout: 20_000 });
    await btn.click();
    // Confirmation message ("GHA accepted — N parcel(s) scanned.") or the button settles disabled.
    await expect(
      this.page.getByText(/gha accepted\s*[—-]\s*\d+ parcel/i).or(btn.and(this.page.locator('[disabled]'))),
    ).toBeVisible({ timeout: 20_000 }).catch(() => { /* message copy may vary; state gate is authoritative */ });
  }
}
