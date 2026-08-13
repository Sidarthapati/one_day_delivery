# Phase 3 run-card — HYD → DEL — **RESUME: the last mile in Delhi**

> One parcel, **Hyderabad → Delhi**. The first mile and the flight are **already done** (see §1). All that's
> left is the **Delhi last mile**, run by **Yashvardhan**. This card is written so Yash can finish it on his
> own, start to end. The only new thing since the first draft is the **Shuttle Agent** — §2 explains it in
> two lines. Do §4 (pre-flight) first, then just follow §5 top to bottom.
>
> **IDs you'll need** (copy-paste):
> - Parcel ref: `1DD-HYD-20260812-00001` · Flight: `6E6025` · AWB id: `c44073c8-2898-4bb2-bcf9-a28e43328d98`
> - DEL shuttle agent (Ash) id: `eeceaa7e-07b2-48c8-8fef-a740312ff41e` · DEL fleet id: `f47ac10b-58cc-4372-a567-0e02b2c3d479`
> - Backend: `https://one-day-delivery.onrender.com`

---

## 1. Where the parcel is RIGHT NOW — read this first

| | Value |
|---|---|
| **Ref** | `1DD-HYD-20260812-00001` |
| **State** | **`LANDED`** — the plane is down in Delhi; parcel is at the DEL airport, waiting to be collected |
| **Lane** | HYD → DEL, INTERCITY, 900 g, PREPAID |
| **AWB** | `312-80606072` · **AWB id** `c44073c8-2898-4bb2-bcf9-a28e43328d98` |
| **Flight** | `6E6025`, flight_date **2026-08-12**, `flight_instance` status **LANDED** |
| **Drop** | Prasanna Apartment, Model Town, New Delhi 110033 (~28.7159, 77.1445) |
| **DEL delivery mode** | **`HUB_RETURN`** ✅ (already set — delivery auto-assigns, no van needed) |

**Done already** (steps 1–11 of the old chain, forced through ops-recovery where the M7 hub consumer was thin):
`BOOKED → PICKUP_ASSIGNED → PICKED_UP → RETURNED_TO_HUB → AT_ORIGIN_HUB → ORIGIN_HUB_PROCESSING →
IN_TAKEOFF_BAG → DISPATCHED_TO_AIRPORT → AT_AIRPORT → DEPARTED → LANDED`.

**Left to do** (this card):
`LANDED → DISPATCHED_TO_HUB → AT_DEST_HUB → DEST_HUB_PROCESSING → HUB_DELIVERY_ASSIGNED → COLLECTED_FROM_HUB → DROPPED`.

---

## 2. What the Shuttle Agent is (the only new thing)

A **shuttle agent** is the person who drives flight bags between a city's hub and its airport. Those two
short legs used to be fake button-presses on the hub/airline consoles; now a real person does them in the
**driver app**, and they show as a **live moving dot** on tracking. Same states as before — only *who taps*
changed:

| Leg | Old trigger | New trigger |
|---|---|---|
| Origin **hub → airport** (`… → DISPATCHED_TO_AIRPORT`) | Hub console "Dispatch to airport" | **Shuttle app → "Out to airport"** (HYD agent) |
| Dest **airport → hub** (`LANDED → DISPATCHED_TO_HUB`) | Airline console "Dest shuttle-in" | **Shuttle app → "Collected from airport"** (DEL agent) |

For **this parcel**, origin-out already happened, so **only the dest collect is a shuttle action** — done by
the **DEL shuttle agent (Ash)**. The airline console's "Dest shuttle-in" button is **gone** (moved to the app).

> ### The shuttle "From airport" list shows anything landed-and-not-yet-collected
> Ash's list shows **every flight that has landed at Delhi airport and hasn't been brought to the hub yet** —
> it does **not** matter what day the flight was. So our parcel (landed on the Aug-12 flight, never collected)
> **shows up whenever Ash opens the app.** He taps **Collected from airport**, it disappears from the list,
> and the parcel moves on. No SQL, no workarounds.
>
> *(This used to be broken — the list only showed "today's" flights, so an Aug-12 parcel vanished on Aug-13.
> Fixed by `airline V9_12` + the state-based queries. **Pre-flight L0 below just checks that fix is live.**)*

---

## 3. Logins

| Persona | Where | Login | Used for |
|---|---|---|---|
| **Shuttle agent — DEL (Ash)** | Godspeed **driver app**, needs the build with the shuttle persona | `ash.shuttle@oneday.in` / `godspeed2026` | **Collect from airport** (step S1) |
| Shuttle agent — HYD (Agniva) | driver app | `agniva.shuttle@oneday.in` / `godspeed2026` | outbound leg — only for a *fresh* parcel (§6) |
| Hub console — DEL | `godspeed-hub.vercel.app` | `admin@oneday.in` / `godspeed2026`, select **DEL** | **dock scan-in** (step S2) |
| Delivery DA — DEL (Yash) | driver app | `yash.s1@oneday.test` (SHIFT_1) / `godspeed2026` | **deliver** (step S4) |
| Admin / API | backend | `admin@oneday.in` / `godspeed2026` | OTP peek, curl fallbacks |
| Backend base | `https://one-day-delivery.onrender.com` | — | API |

- Both shuttle agents are **registered and verified** (Admin console → "Shuttle Agents"; role `SHUTTLE_AGENT`,
  Ash=DEL, Agniva=HYD). First login shows a **"change password"** prompt — it does **not** block the token,
  so Ash can just proceed (or set a new password once).
- Yash wears **three hats** in this last mile: **shuttle agent (Ash)** to collect, **hub operator** to scan
  in, **delivery DA (yash.s1)** to deliver. That's expected.

---

## 4. Pre-flight for the last mile — do these before you start

| # | Check | How |
|---|---|---|
| **L0** | **Shuttle-queue state-fix deployed** (`airline V9_12` + state-based queries) | So the LANDED Aug-12 parcel shows in Ash's app regardless of date. Confirm after deploy: `GET $BASE/shuttle/eeceaa7e-07b2-48c8-8fef-a740312ff41e/queue` (as Ash) → the AWB appears under `inbound`. If `inbound` is empty, L0 isn't live yet. |
| **L1** | **DEL delivery mode = `HUB_RETURN`** | ✅ already set. Confirm: `GET $BASE/routing/fleet/f47ac10b-58cc-4372-a567-0e02b2c3d479` → `"meeting_mode":"HUB_RETURN"`. |
| **L2** | **DEL DA plan APPROVED + roster loaded for the run day** | Without an approved DEL territory plan + a loaded shift, `HUB_DELIVERY_ASSIGNED` never fires. Approve for the run day, then load: `POST $BASE/dispatch/admin/shift-load?date=<run-day>&shift=SHIFT_1`. (SHIFT_1 auto-loads 05:45 IST; force it if you start earlier.) |
| **L3** | **`yash.s1` online in the driver app** | Log in as `yash.s1@oneday.test`, toggle **online** — needed for delivery auto-assign. |

---

## 5. The last mile — step by step (actor · action · state)

| # | Log in as | What Yash does | Parcel moves to | How to confirm |
|---|---|---|---|---|
| **S1** | **`ash.shuttle`** in the **driver app** | The app opens on the shuttle screen. Under **"From airport"**, find flight `6E6025` and tap **Collected from airport**. (Keep the app open a minute so its GPS shows a live dot on tracking.) | `DISPATCHED_TO_HUB` | the flight disappears from Ash's list; tracking shows **"En route to delivery hub"** |
| **S2** | **`admin@oneday.in`** on the **hub console** (`godspeed-hub.vercel.app`), pick **DEL** | Find the parcel, click **Receive**, then **scan it in** at the dock. It auto-sorts for delivery. | `AT_DEST_HUB → DEST_HUB_PROCESSING` | tracking shows **"At delivery hub"** |
| **S3** | *(nothing — automatic)* | The system assigns the delivery to whoever is the online DEL driver (that's Yash). | `HUB_DELIVERY_ASSIGNED` | a **DROP task** pops up in Yash's driver app (S4 login) |
| **S4** | **`yash.s1`** in the **driver app** (must be **online**) | Open the DROP task → **Collect from hub** → drive to Prasanna Apartment → **I've arrived** → **scan** → enter the **recipient OTP**. | `COLLECTED_FROM_HUB → DROPPED` ✅ | tracking shows **Delivered** 🎉 |

- **Delivery has no cron/feasibility gate** (that's pickups only) — once assigned + online, Yash delivers immediately.
- OTP peek if SMS isn't wired: `GET $BASE/internal/dev/shipments/1DD-HYD-20260812-00001/delivery-otp` (admin token, `!prod`).

**Watch it live:** open the **track** page for the ref (business/customer). Milestones should advance
**Landed → Out for delivery → Delivered**, with a moving dot on the airport→hub leg (if Ash pings) then on Yash.

---

## 6. If something stalls — quick triage

| Symptom | Likely cause | Fix |
|---|---|---|
| **Parcel not in Ash's "From airport" list** | L0 fix not deployed yet, **or** flight instance isn't `LANDED`, **or** it was already collected | confirm L0 is live; check `flight_instance` status = LANDED for `6E6025`; check the AWB isn't already stamped (`dest_collected_at`) |
| Collect returns "already handled" | the other agent already collected it | check state: `GET $BASE/shipments/…` — if already `DISPATCHED_TO_HUB`, skip to S2 |
| Dock scan 409 "not yet handed off" | parcel still `LANDED` | do S1 (collect) first |
| **Delivery never assigns (S3)** | DEL DA plan not approved / shift not loaded / Yash offline | L2 + L3, then **re-scan** the parcel at the DEL hub |
| Delivery OTP rejected | OTP wiring | peek endpoint above |

---

## 7. (Optional) Fresh full E2E — to exercise **both** shuttle legs

The resume above only shows the **dest** shuttle leg (origin-out was already done for this parcel). To demo
the **whole** shuttle feature end to end, book a **new** HYD → DEL parcel on the run day and add these two
shuttle steps to the standard chain:

- **HYD origin-out:** after the hub **seals** the flight bag, **Agniva (HYD shuttle agent)** opens "To airport",
  (optionally multi-selects sealed bags), taps **Out to airport** → `DISPATCHED_TO_AIRPORT` + live dot.
  If a bag is still OPEN, tap **Request seal** (badges the hub console) or wait for the auto-seal backstop.
- **DEL dest-collect:** exactly step **S1** above.

Everything between (GHA accepted on the airline console → auto DEPARTED/LANDED → DEL dock scan → HUB_RETURN
delivery) is unchanged. Fast-forward the flight if you don't want to wait:
```sql
UPDATE flight_instance SET departure = now() + interval '2 min', arrival = now() + interval '4 min'
 WHERE flight_no = '<FLIGHT_NO>' AND flight_date = CURRENT_DATE;
```

---

**Bottom line for tomorrow:** the parcel is sitting at **LANDED** in Delhi with everything downstream green
(DEL = HUB_RETURN, both shuttle agents registered and working, queue is now state-based). Once the **L0
state-fix is deployed**, the parcel just shows in Ash's app — then it's four screen taps (S1→S4) to
**DELIVERED**. No SQL, no curl.
