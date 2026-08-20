# Maestro driver-app E2E

Smoke flow for the React Native driver app (native Android). Covers the critical DA path:
launch → login → today's queue → open task → manual "I've arrived".

## Run

```bash
# Install Maestro: https://maestro.mobile.dev
export DRIVER_EMAIL=... DRIVER_PASSWORD=...        # a seeded DA on staging
maestro test e2e/maestro/driver-smoke.yaml
```

Needs a connected Android emulator/device with the app installed (`in.godspeed.driver`).

## Status

Scaffold. Selectors (`id:`/`text:`) are placeholders — align them to the app's `testID` /
accessibility labels, then drop the `optional:` markers as flows stabilise. The "I've arrived" step
reflects the manual arrival that replaced the 200m geofence. Not yet wired into CI (needs an emulator
runner + a signed build); run locally / in a device-farm job for now.
