import { expect, type Page } from '@playwright/test';
import { CONSOLES } from '../playwright.config';

/**
 * Drives the real Mantine login form each console ships (no shared component — every app has its own
 * copy). They differ only in the email field label and the post-login landing route, so one POM
 * parameterised per app covers all six. Proving login through the UI (rather than seeding a token in
 * localStorage) is deliberate: it keeps the suite honest that the auth screen actually works.
 */
type AppName = keyof typeof CONSOLES;

const EMAIL_LABEL: Record<AppName, RegExp> = {
  business: /work email/i,
  customer: /email/i,
  hub: /email/i,
  station: /email/i,
  airline: /email/i,
  admin: /email/i,
};

// Where each app lands after a successful sign-in (business → /dashboard; the console apps → /).
const LANDING: Record<AppName, RegExp> = {
  business: /\/dashboard/,
  customer: /\/(home|dashboard)?$/,
  hub: /\/$|\/[^l]/,
  station: /\/$|\/[^l]/,
  airline: /\/$|\/[^l]/,
  admin: /\/$|\/[^l]/,
};

export class LoginPage {
  constructor(private readonly page: Page, private readonly app: AppName) {}

  private get baseUrl() {
    return CONSOLES[this.app];
  }

  /** Navigate to the login screen and sign in, asserting we leave /login for the app shell. */
  async signIn(email: string, password: string): Promise<void> {
    await this.page.goto(`${this.baseUrl}/login`, { waitUntil: 'domcontentloaded' });
    const emailBox = this.page.getByLabel(EMAIL_LABEL[this.app]).first();
    const pwBox = this.page.getByLabel(/password/i).first();
    const signIn = this.page.getByRole('button', { name: /sign in|log in/i });
    await emailBox.waitFor();

    // Both the field fill and the submit click can race Next.js hydration: an input filled before
    // React attaches its controlled value gets reset, and a click before the handler binds no-ops
    // (page stays on /login). Retry the whole fill+submit until we actually leave /login.
    for (let attempt = 0; attempt < 4; attempt++) {
      for (let f = 0; f < 3; f++) {
        await emailBox.fill(email);
        await pwBox.fill(password);
        if ((await emailBox.inputValue()) === email && (await pwBox.inputValue()) === password) break;
        await this.page.waitForTimeout(300);
      }
      await signIn.click();
      try {
        await expect(this.page).not.toHaveURL(/\/login/, { timeout: 8_000 });
        break; // left /login → signed in
      } catch {
        if (attempt === 3) throw new Error(`${this.app} sign-in stayed on /login after 4 attempts`);
      }
    }
    await expect(this.page).toHaveURL(LANDING[this.app]);
  }
}
