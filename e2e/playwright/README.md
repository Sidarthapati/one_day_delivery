# Playwright E2E (six consoles)

Browser smoke tests for the deployed Godspeed consoles (customer, business, hub, station, airline,
admin). This is the **scaffold**: each test asserts the console loads and renders a real app shell
(no error boundary). Deepen into login→dashboard→core-action per console as seeded staging accounts
land (see the skipped `customer: login → home` template).

## Run

```bash
cd e2e/playwright
npm install
npx playwright install --with-deps chromium
npm test                      # against the default *.vercel.app aliases
CUSTOMER_URL=https://preview… npm test   # override any console URL via env
```

## CI

`.github/workflows/playwright.yml` runs these on demand (`workflow_dispatch`) — the consoles are
deployed separately (Vercel), so this isn't on the backend PR gate. Console URLs come from repo
variables (`vars.CUSTOMER_URL`, …) or fall back to the stable aliases.

## Driver app

The React Native driver app is covered separately by Maestro — see [`../maestro/`](../maestro/).
