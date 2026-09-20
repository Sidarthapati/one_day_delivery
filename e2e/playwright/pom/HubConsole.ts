import { expect, type Page } from '@playwright/test';
import { CONSOLES } from '../playwright.config';
import { CITY_UUID, type CityCode } from '../../harness/index';

/**
 * Hub operator console. One operator drives both hubs by switching the header hub picker (persisted
 * in localStorage as `od_hub_selected` = the hub UUID). We set it directly rather than clicking the
 * picker mid-flow so the switch is deterministic; the picker itself is covered by the smoke spec.
 *
 * Bag detail / delivery pages are reached by the bagId the control plane already resolved from the
 * shipment (via SQL), so we navigate straight to the row's page and click the real action — more
 * robust than scraping a paginated table, while still exercising the seal button through the UI.
 */
export class HubConsole {
  constructor(private readonly page: Page) {}

  /** Point the console at a city's hub. localStorage is per-origin, and the flow visits other console
   *  origins (airline :3005) between hubs — so land on the hub origin FIRST, then write the key there,
   *  else it lands on whatever origin the page was last on and the hub silently stays unchanged. */
  async selectHub(city: CityCode): Promise<void> {
    await this.page.goto(`${CONSOLES.hub}/`, { waitUntil: 'domcontentloaded' });
    await this.page.evaluate((id) => localStorage.setItem('od_hub_selected', id), CITY_UUID[city]);
  }

  /** Dock scan-in one parcel; gate on it appearing in the results table. */
  async receive(ref: string): Promise<void> {
    await this.page.goto(`${CONSOLES.hub}/receive`, { waitUntil: 'domcontentloaded' });
    await expect(this.page.getByRole('heading', { name: /receive/i })).toBeVisible();
    const scan = this.page.getByPlaceholder(/1DD-/).first();
    await scan.fill(ref);
    await this.page.getByRole('button', { name: /^Scan$/ }).click();
    // The received parcel renders as a row with its ref in a .od-mono cell.
    await expect(this.page.locator('.od-mono', { hasText: ref })).toBeVisible({ timeout: 30_000 });
  }

  /** Open a flight bag and seal it (emits BAG_SEALED → AWB booking). */
  async sealFlightBag(bagId: string): Promise<void> {
    await this.page.goto(`${CONSOLES.hub}/bags/${bagId}`, { waitUntil: 'domcontentloaded' });
    const seal = this.page.getByRole('button', { name: /seal & generate manifest/i });
    await expect(seal).toBeEnabled({ timeout: 20_000 });
    await seal.click();
    // Once sealed the bag is no longer OPEN, so the action disables and the manifest renders.
    await expect(seal).toBeDisabled({ timeout: 20_000 });
  }

  /** Seal every open delivery bag on the current hub's delivery board (drives HUB_DELIVERY_ASSIGNED). */
  async sealOpenDeliveryBags(): Promise<void> {
    await this.page.goto(`${CONSOLES.hub}/delivery`, { waitUntil: 'domcontentloaded' });
    await expect(this.page.getByRole('heading', { name: /delivery bags/i })).toBeVisible();
    const sealButtons = this.page.getByRole('button', { name: /^Seal$/ });
    await expect(sealButtons.first()).toBeVisible({ timeout: 30_000 });
    // Seal in a loop: each click removes that row's button (row flips OPEN→SEALED in place).
    for (let guard = 0; guard < 20; guard++) {
      const count = await sealButtons.count();
      if (count === 0) break;
      await sealButtons.first().click();
      await expect(sealButtons).toHaveCount(Math.max(0, count - 1), { timeout: 20_000 });
    }
  }
}
