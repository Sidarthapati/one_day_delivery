import { expect, type Page } from '@playwright/test';
import { CONSOLES } from '../playwright.config';
import type { CityCode } from '../../harness/index';

/**
 * Books a parcel through the real "Godspeed for Business" ship form — the truest booking path: a
 * Google Places address search resolves each endpoint to lat/lon, the backend confirms
 * serviceability, and only then does the Book button enable. Confirmed on localhost that both the
 * map render and Places autocomplete work with the shipped NEXT_PUBLIC_GOOGLE_MAPS_API_KEY.
 *
 * The form has two identical field groups ("Pickup from" then "Deliver to"); their labels repeat, so
 * we address them by DOM order — nth(0) = sender, nth(1) = receiver.
 */

// A real, serviceable landmark per city — resolves inside the seeded H3 box so pickups auto-assign.
const CITY_ADDRESS: Record<CityCode, string> = {
  DEL: 'Connaught Place, New Delhi',
  HYD: 'Banjara Hills, Hyderabad',
};

// The form derives the pincode from the resolved place's postal_code, but Google occasionally omits
// it — leaving the required 6-digit Pincode field empty, which keeps Book disabled. Fill it always.
const CITY_PINCODE: Record<CityCode, string> = { DEL: '110001', HYD: '500001' };

export interface BookInput {
  senderCity: CityCode;
  receiverCity: CityCode;
  cod?: number; // paise; set → toggles COD and fills the collect amount
  declaredValuePaise?: number; // required for B2B; default ₹500
}

export class BusinessShipPage {
  constructor(private readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto(`${CONSOLES.business}/ship`, { waitUntil: 'domcontentloaded' });
    // The form only mounts once the Google Maps <APIProvider> is ready; the heading is our anchor.
    await expect(this.page.getByRole('heading', { name: /ship a parcel/i })).toBeVisible();
  }

  /** Fill one party's Address via Places autocomplete and wait for serviceability to resolve. */
  private async fillAddress(which: 'sender' | 'receiver', city: CityCode): Promise<void> {
    const idx = which === 'sender' ? 0 : 1;
    const box = this.page.getByRole('textbox', { name: 'Address' }).nth(idx);
    await box.click();
    await box.fill(CITY_ADDRESS[city]);
    // Places is debounced ~300ms then hits the network; wait for the suggestion listbox.
    const firstOption = this.page.getByRole('option').first();
    await firstOption.waitFor({ state: 'visible', timeout: 15_000 });
    await firstOption.click();
    // Selecting a place fires api.grid.serviceableAt; the Pincode field appears once resolved.
    const pincode = this.page.getByRole('textbox', { name: 'Pincode' }).nth(idx);
    await expect(pincode).toBeVisible({ timeout: 15_000 });
    await pincode.fill(CITY_PINCODE[city]);
  }

  /** Fill both parties + parcel, submit, and return the booked shipment_ref. */
  async book(input: BookInput): Promise<string> {
    const declared = ((input.declaredValuePaise ?? 50_000) / 100).toString();

    // Contact details (labels repeat per party → nth index).
    await this.page.getByRole('textbox', { name: 'Contact name' }).nth(0).fill('E2E Sender');
    await this.page.getByRole('textbox', { name: 'Phone' }).nth(0).fill('9000000001');
    await this.fillAddress('sender', input.senderCity);

    await this.page.getByRole('textbox', { name: 'Contact name' }).nth(1).fill('E2E Receiver');
    await this.page.getByRole('textbox', { name: 'Phone' }).nth(1).fill('9000000002');
    await this.fillAddress('receiver', input.receiverCity);

    // Parcel — weight/dims default (1kg, 20×15×10); declared value is required for B2B.
    await this.page.getByRole('textbox', { name: 'Declared value (₹)' }).fill(declared);

    if (input.cod && input.cod > 0) {
      // Mantine Switch hides the real <input> under a styled track; neither click() (track intercepts)
      // nor check() (input is zero-size/hidden) work — click the visible track itself. One switch here.
      await this.page.locator('.mantine-Switch-track').first().click();
      await this.page
        .getByRole('textbox', { name: /amount to collect from recipient/i })
        .fill((input.cod / 100).toString());
    }

    // The Book button (text varies: "Book on credit" / "Book & pay from wallet") only enables once
    // both endpoints are serviceable and the parcel is valid — so this is also the serviceability gate.
    const book = this.page.getByRole('button', { name: /^Book/ });
    await expect(book, 'Book stayed disabled — an endpoint is not serviceable or a field is invalid')
      .toBeEnabled({ timeout: 20_000 });
    await book.click();

    // Success swaps the form for the Booked panel.
    await expect(this.page.getByRole('heading', { name: /shipment booked/i })).toBeVisible({ timeout: 30_000 });
    const refNode = this.page.locator('.od-mono').filter({ hasText: /1DD-/ }).first();
    await expect(refNode).toBeVisible();
    const ref = (await refNode.innerText()).trim();
    if (!/^1DD-/.test(ref)) throw new Error(`Booked panel did not yield a shipment_ref (got "${ref}")`);
    return ref;
  }
}
