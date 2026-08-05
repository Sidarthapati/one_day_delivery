# Godspeed — Monthly Platform Cost Analysis

> **Prepared:** August 2026 · **Owner:** Sid · **Scope:** end-to-end recurring cost to *operate* the Godspeed platform (infra, third-party APIs, tooling, dev subscriptions). **Excludes** salaries, ground-ops (DAs/vans/fuel), air freight (AWB), and rent — those are operational COGS, not platform cost.
>
> **FX used:** ₹88 = $1 (Aug 2026). **India-priced** services (Google Maps, SMS, KYC) use the discounted India rates in ₹. **USD-global** services (Render, Vercel, CloudAMQP, Hetzner, Claude, Sentry, Expo, flight API) are billed in USD worldwide — India location does *not* discount these.
>
> **Design bias per instruction:** premium/zero-lag tiers on compute. Cheaper tiers noted where genuinely sufficient at pilot volume.
>
> **v2 (this revision)** corrects six items from v1: Render Pro Ultra is **$450** (not $700); **Google Maps uses India pricing** (was wrongly on US rates — the single biggest fix); **Claude Max is 5× = $100/dev**; **Expo/Vercel are frontend/build layers, ~$20**, not runtime compute; **Hetzner caps ~$60–70** (OSRM is memory-bound, not volume-bound).

---

## 1. The three volume scenarios

Everything usage-based (Maps, SMS, email, KYC, payment fees) scales with these. Sourced from `docs/godspeed/` (business plan Annexures) and `docs/RUNWAY-JULY-2026.md`.

| Driver | **A · Pilot** (Sept '26) | **B · Early growth** (~Q1 '27) | **C · Scale** (Year-1 target) |
|---|---|---|---|
| Lane coverage | DEL↔BOM only | 10-city, partial lanes | 10-city / 90-lane full |
| Parcels / day | ~150 | ~2,000 | ~10,000 |
| Parcels / month | ~4,500 | ~60,000 | ~300,000 |
| OTP logins / month | ~3,600 | ~44,000 | ~200,000 |
| Active DAs | ~20 | ~150 | ~600 |
| KYC onboardings / month | ~30 | ~200 | ~500 |
| Night flights tracked | ~2–4 | ~30 | ~90 |

---

## 2. Headline — total monthly platform cost

| | **A · Pilot** | **B · Growth** | **C · Scale** |
|---|--:|--:|--:|
| **Total / month (USD)** | **≈ $1,460** | **≈ $3,900** | **≈ $12,570** |
| **Total / month (INR)** | **≈ ₹1.29 L** | **≈ ₹3.43 L** | **≈ ₹11.06 L** |
| *Cost per parcel* | ₹29 | ₹5.7 | ₹3.7 |

> **What drives the bill at each stage:**
> - **Pilot:** **Claude Max (5 devs) = $500 is ~34% of the bill.** It's fixed — doesn't scale with parcels. Everything else sits in free/entry tiers; Google Maps is **₹0** (inside India's 70k-free-events/SKU tier).
> - **Scale:** the top four are **Google Maps ($4,398), SMS ($2,386), Render compute+DB ($2,550), CloudAMQP ($499)** — balanced, no single runaway line. Maps is still #1 and remains the reason to evaluate Ola Maps + in-app SDK (§5).
>
> **Excluded from totals** (revenue-linked pass-through COGS, see §7): Razorpay/RazorpayX ≈ ₹9.6k / ₹1.27L / ₹6.37L per month for A/B/C.

---

## 3. Full line-item breakdown

India-priced lines shown in ₹ with USD in brackets; USD-global lines in $.

| # | Component | Vendor / tier | Billing | **A · Pilot** | **B · Growth** | **C · Scale** |
|---|---|---|---|--:|--:|--:|
| **Compute & hosting (USD-global)** |||||||
| 1 | Backend API + workers | Render — Pro Ultra 32GB/8CPU (×1→3) | USD | $450 | $900 | $1,350 |
| 2 | Managed Postgres | Render Postgres (Basic→Pro) | USD | $50 | $300 | $1,200 |
| 3 | Frontends (customer/business/admin) | Vercel Pro $20/seat + usage | USD | $20 | $60 | $150 |
| 4 | Message broker | CloudAMQP dedicated (Lemur→Ermine) | USD | $99 | $199 | $499 |
| 5 | OSRM routing engine | Hetzner Cloud (CCX, self-host) — **caps ~$70** | USD | $30 | $60 | $70 |
| **Customer-facing APIs** |||||||
| 6 | **Google Maps** (autocomplete + geocode + tracking maps) | Google Maps Platform **India pricing** | ₹ (INR) | **₹0** | ₹53k ($607) | ₹3.87L ($4,398) |
| 7 | Flight live-tracking | AeroDataBox / FlightAware AeroAPI | USD | $32 | $160 | $400 |
| 8 | Map My India (order addresses) | Mappls | ₹/USD | $20 | $50 | $150 |
| **Messaging** |||||||
| 9 | SMS (OTP + alerts) | Airtel IQ / DLT @ ₹0.15 | ₹ (INR) | ₹3,240 ($37) | ₹42,600 ($484) | ₹2.10L ($2,386) |
| 10 | Email (transactional) | SendGrid / AWS SES | USD | $20 | $90 | $150 |
| 11 | Push notifications | Expo Push + FCM | — | $0 | $0 | $0 |
| **Identity & compliance** |||||||
| 12 | KYC / verification | sandbox.co.in (PAN+Aadhaar+bank @ ~₹5) | ₹ (INR) | ₹450 ($5) | ₹3,000 ($34) | ₹7,500 ($85) |
| **Observability** |||||||
| 13 | Error tracking (web + mobile) | Sentry Business + RN/Expo | USD | $80 | $200 | $500 |
| 14 | Logs / APM / uptime | Better Stack / Grafana Cloud | USD | $50 | $150 | $400 |
| **Mobile delivery (build/OTA, NOT runtime)** |||||||
| 15 | Expo EAS (builds + OTA updates) | EAS Starter $19 → Production $199 | USD | $19 | $19 | $99 |
| 16 | App-store accounts | Google Play ($25 once) + Apple ($99/yr) | USD | $10 | $10 | $10 |
| **Storage & edge** |||||||
| 17 | Object storage + CDN (labels, scans, exports) | AWS S3/GCS + Cloudflare | USD | $20 | $60 | $200 |
| 18 | Domains / DNS / WAF | Cloudflare Pro + registrar | USD | $20 | $20 | $20 |
| **AI dev tooling** |||||||
| 19 | **Claude Max × 5 devs** | Anthropic **Max 5× ($100 ea)** | USD | $500 | $500 | $500 |
| | **TOTAL / month (USD)** | | | **≈ $1,462** | **≈ $3,903** | **≈ $12,567** |
| | **TOTAL / month (₹)** | | | **≈ ₹1.29 L** | **≈ ₹3.43 L** | **≈ ₹11.06 L** |

> **Why Render still shows $450 at pilot:** that's the **Pro Ultra (32GB/8CPU)** premium tier you asked for. 150 parcels/day genuinely doesn't need it — a **Standard (2GB/1CPU, $25)** or **Pro (8GB/2CPU, ~$85)** instance would run the pilot with zero lag and save ~$365–425/mo. Kept at Pro Ultra here per the "best compute" instruction; flag if you'd rather see the right-sized number as the headline.
>
> **Expo/Vercel are cheap on purpose — you're right.** They are the **frontend + build/OTA layer**, not runtime compute. When a user taps a button, it's an API call to **Render** (the real compute cost). Vercel just serves static/edge React; Expo EAS just *builds* the app binary and ships OTA JS updates. Neither runs your business logic. So both stay ~$20 until frontend bandwidth (Vercel) or app MAU (Expo) genuinely grow.

---

## 4. Your specific questions, answered

### Q1 — "How much does it actually cost to show the live-tracking page for one parcel? Is it per second?"

**No — it is NOT per second, per marker move, or per GPS ping. It's per *map load*.** (India Dynamic Maps rate: **$2.10 / 1,000 loads ≈ ₹0.18 per load**.)

- A **"map load"** = one time the map *initialises* on screen (page open / app screen mount). That's the only billable moment.
- Once loaded, **everything after is free**: leaving it open for 1 second or 1 hour = same 1 load; panning, zooming = free; **your driver GPS dot moving every 12s = free** (you're repositioning your own marker on an already-loaded map — Google never sees or bills your lat/long).
- **So the cost to track one parcel = ₹0.18 × (number of times the customer opens the tracking page).** If a buyer opens it ~3–4 times across the parcel's life ≈ **₹0.55–0.75 per parcel** — and the **first 70,000 loads every month are free** (India tier), so at pilot it's literally **₹0**.
- **The only way this cost grows is more page-opens**, not longer viewing. Refreshing the browser = a new load.

### Q2 — "We source the driver GPS ourselves (Expo native). Does *displaying* it on Google Maps cost money?"

Yes, but only the **map canvas**, and only **on the web**:
- **Web tracking page** (Maps JavaScript): billed per map load — ₹0.18/load as above (first 70k/mo free).
- **In-app native Maps SDK (Android/iOS):** **₹0 — unlimited free.** Google does not charge for dynamic map loads inside a mobile app.
- **Takeaway:** show live-tracking inside the **app** (free) wherever possible; the **web** tracking page is the only surface that accrues map-load cost — and even that is free up to 70k loads/month.

### Q3 — "Is there a live-flight-location provider, and what does it cost?"

Yes. Modeled **$32 → $160 → $400** (A/B/C):

| Provider | Model | Fit |
|---|---|---|
| **AeroDataBox** (RapidAPI) | Free 600 units → Pro $5.35 → Ultra $32 → Mega $160/mo | Best value, good India coverage — **A/B** |
| **FlightAware AeroAPI v3** | Pay-per-query, ~$5 free credit | Most accurate live positions — **C** |
| **AviationStack** | $49.99 (10k) → $499.99 (250k)/mo | Fixed buckets, simple to budget |

---

## 5. Google Maps in India — the numbers, in ₹

India gets **~70% lower Core Services pricing, billed in INR, with 70,000 free events *per SKU* per month** (vs 10k globally). Google frames the summed per-SKU free tiers as *"up to $6,800/month of free usage"* — it's the **aggregate value of the per-SKU allowances**, not an extra pooled credit, so we don't double-count it.

| Surface | SKU (India rate) | Events / parcel |
|---|---|---|
| Booking map + tracking views | Dynamic Maps — **$2.10/1,000** (₹0.18) | ~4 |
| Pickup + drop address entry | Autocomplete **$0.85/1,000** + Place Details **$1.50/1,000** | ~6 + ~1.2 (saved addresses cut ~40%) |
| Pin → pincode reverse geocode | Geocoding — **$1.50/1,000** | ~0.5 |

Worked out against the 70k-free-per-SKU tiers:

| | **A · Pilot** | **B · Growth** | **C · Scale** |
|---|--:|--:|--:|
| Dynamic Maps loads | 18k → **free** | 240k → $357 | 1.2M → $2,373 |
| Autocomplete + Details | 32k → **free** | 432k → $250 | 2.16M → $1,905 |
| Geocoding | 2k → **free** | 30k → **free** | 150k → $120 |
| **Maps total** | **₹0** | **≈ ₹53k ($607)** | **≈ ₹3.87L ($4,398)** |

**Levers:** (1) in-app SDK for tracking = free; (2) saved addresses already cut Autocomplete; (3) **Ola Maps** on web (your stated plan) — India-first, cheaper still; (4) Static Maps ($0.60/1k) for the tracking thumbnail.

---

## 6. Costs previously missing + future/predicted

**Now in §3:** Sentry (web+mobile), Better Stack/Grafana (logs/APM/uptime), Expo EAS, app-store accounts, object storage+CDN, WAF/DNS, Map My India.

**Predict / budget before go-live (not in headline):**

| Item | Why | Rough cost |
|---|---|---|
| **DLT registration** (SMS) | Mandatory for Indian transactional SMS | ₹5,900 once + ₹590/header/yr |
| **PagerDuty / Opsgenie** | Real parcels → paging on RED SLA / outages | $20–40/user/mo |
| **Product analytics** (PostHog/Mixpanel) | Funnel, retention, WISMO (M11 R6) | Free → $200/mo |
| **CI/CD** (GitHub Actions) | Maven multi-module + RN builds | $0–100/mo |
| **Support tooling** (Freshdesk/Zendesk) | WISMO tickets during pilot | $15–50/agent/mo |
| **Secrets** (Doppler / cloud KMS) | Formalise Razorpay-key handling | $0–20/mo |
| **DB backups / PITR & DR** | Cross-region snapshots for prod | $20–100/mo |
| **Pentest / security review** | Real payments + KYC PII | ₹1.5–4L/yr |
| **Load/soak testing** (k6 Cloud) | Validate zero-lag under wave bursts | $0–99/mo when running |
| **RazorpayX payouts** | COD settlement, DA reimbursements | ₹2–5/payout |
| **Ola Maps / Mappls contract** | The Maps migration target | TBD, << Google at scale |

---

## 7. Payment gateway fees (pass-through COGS — tracked separately)

Razorpay = **2% + 18% GST = 2.36%** of processed value; COD-to-merchant flat **₹7/parcel**. Assuming ~60% prepaid at ₹150 delivery fee:

| | A · Pilot | B · Growth | C · Scale |
|---|--:|--:|--:|
| Razorpay fees / month | ≈ ₹9,600 ($109) | ≈ ₹1.27 L ($1,448) | ≈ ₹6.37 L ($7,241) |

Recovered in pricing (M2 models COD ₹7), so revenue-neutral-ish — **excluded from §2/§3 totals.**

---

## 8. Annual & one-time costs

| Item | Cost |
|---|---|
| Google Play Developer | $25 one-time |
| Apple Developer Program | $99 / yr |
| DLT entity registration | ~₹5,900 one-time |
| DLT sender headers | ₹590 / header / yr |
| Domain registrations | ~₹1,000–2,000 / yr |
| Penetration test | ₹1.5–4 L / yr (recommended) |

---

## 9. Recommendations

1. **Pilot runs on ~₹1.29 L/mo** even on premium compute. Biggest single line is **Claude Max ($500)** — fixed to team size, ₹0.03/parcel at scale, the cheapest leverage in the stack.
2. **Right-size Render for pilot.** Pro Ultra (32GB/8CPU, $450) is huge for 150 parcels/day. A Pro instance (~$85) likely runs the pilot with zero lag → **saves ~₹32k/mo**. Scale up when B arrives.
3. **India Maps pricing already saved you the biggest worry.** With the 70k-free-per-SKU tier, Maps is **₹0 at pilot** and only ~₹3.9L even at 300k parcels/day — and moving tracking into the app SDK + Ola Maps keeps it well below that.
4. **Add observability now** (Sentry + Better Stack, ~$130/mo). Real parcels + real payments = flying blind is the expensive option.
5. **Expo/Vercel stay ~$20 each** — they're the frontend/build layer, not compute. Don't over-provision them; scale only when app MAU (Expo) or edge bandwidth (Vercel) actually climb.

---

## Sources (2026 published rates)

- **Google Maps India pricing** — [developers.google.com/maps India pricing](https://developers.google.com/maps/billing-and-pricing/pricing-india), [mapsplatform.google.com/intl/en_in](https://mapsplatform.google.com/intl/en_in/pricing/), [Google India blog](https://blog.google/intl/en-in/products/explore-communicate/helping-developers-in-india-build-more-with-google-maps-platform/)
- Indian SMS/OTP + DLT — [messagecentral.com](https://www.messagecentral.com/blog/sms-otp-pricing-india), [Airtel DLT](https://dltconnect.airtel.in/faq/)
- sandbox.co.in KYC — [sandbox.co.in/pricing](https://sandbox.co.in/pricing)
- Claude Max (5× = $100) — [claude.com/pricing](https://claude.com/pricing)
- Render (Pro Ultra $450) — [costbench.com/render](https://costbench.com/software/developer-tools/render/)
- Vercel — [costbench.com/vercel](https://costbench.com/software/developer-tools/vercel/)
- CloudAMQP — [cloudamqp.com/plans](https://www.cloudamqp.com/plans.html)
- Expo EAS (Starter $19 / Production $199) — [docs.expo.dev/billing/plans](https://docs.expo.dev/billing/plans/)
- FlightAware / AeroDataBox / AviationStack — [aerodatabox.com/pricing](https://aerodatabox.com/pricing/), [flightaware.com/commercial/aeroapi](https://www.flightaware.com/commercial/aeroapi/)
- Sentry — [middleware.io/sentry-pricing](https://middleware.io/blog/sentry-pricing/)
- SendGrid — [sendx.io/sendgrid-pricing](https://www.sendx.io/blog/sendgrid-pricing)
- Razorpay — [razorpay.com](https://razorpay.com/blog/razorpay-payment-gateway-pricing-explained/)

---

## Appendix A — What each compute tier actually buys you (plain terms)

> The question isn't "how many *users*." A million people with the app installed but idle = ~zero load. What matters is **requests per second (RPS)** — how many people are hitting the backend *at the same moment* — plus **memory** (how much the server holds at once). This appendix translates every tier into "how much traffic before it hurts."

### A.1 First: how much traffic do we actually generate?

The heavy load is **not** bookings — it's **background telemetry**: tracking pages polling every 12s, and DA phones sending GPS pings. Rough request budget:

| Source | Requests | Notes |
|---|---|---|
| Booking + payment | ~15–20 / parcel | one-time, at booking |
| Lifecycle scans | ~10 / parcel | pickup → hub → flight → hub → delivery |
| **Customer tracking polls** | ~100 / parcel | page open, polls every 12s while watched |
| **DA GPS pings** | ~1,900 / DA / shift | every ~15s over an 8h shift |

Worked out to **peak** RPS (traffic clusters into the 5 pickup + 5 delivery waves, so peak ≈ 5–8× the daily average):

| | Requests/day | Avg RPS | **Peak RPS (wave)** |
|---|--:|--:|--:|
| **A · Pilot** (150 parcels, 20 DAs) | ~58,000 | ~0.7 | **~3–5** |
| **B · Growth** (2,000 parcels, 150 DAs) | ~550,000 | ~6 | **~30–50** |
| **C · Scale** (10,000 parcels, 600 DAs) | ~2.45 M | ~28 | **~150–250** |

**So your "500 RPS" question sits just above full-scale peak.** You do not touch that number for a long time. Pilot peak is *single-digit* RPS.

### A.2 Render (the backend — this is where real compute lives)

A Render instance runs your Spring Boot JVM. Two dials:
- **RAM** = how much it holds at once (JVM heap, DB connection pool, in-flight requests). **Run out → `OutOfMemoryError` → the app crashes and restarts.** Java is RAM-hungry: Spring Boot needs ~600MB–1GB just to *boot*.
- **CPU** = how fast each request is served and how many in parallel. **Run out → requests queue → latency climbs → timeouts** (the app doesn't crash, it just gets slow, then times out).

| Render tier | RAM / CPU | Price | Handles roughly | What breaks it |
|---|---|--:|---|---|
| Starter | 512 MB / 0.5 | $7 | a toy; barely boots Java | OOM almost immediately under real load |
| **Standard** | 2 GB / 1 | $25 | **~100–300 RPS**, hundreds of concurrent users | one slow DB query backing up the thread pool |
| **Pro** | 4 GB / 2 | $85 | **~300–600 RPS** | sustained wave load beyond ~600 RPS |
| Pro Plus | 8 GB / 4 | $175 | ~600–1,200 RPS | — |
| **Pro Ultra** | 32 GB / 8 | $450 | **~1,500–3,000+ RPS**, thousands concurrent | rarely the bottleneck; DB becomes the limit first |

*(RPS ranges assume typical DB-backed JSON endpoints; a heavy report endpoint is far lower, a cache hit far higher.)*

**Reading this against your traffic:**
- **Pilot (~5 RPS peak):** a **$25 Standard** instance is *loafing*. Pro Ultra ($450) is ~600× your pilot need — pure idle capacity.
- **Growth (~50 RPS peak):** a **$85 Pro**, or 2× Standard, is comfortable.
- **Scale (~250 RPS peak):** **2–3× Pro/Pro-Ultra behind a load balancer.**

> **The single most important point:** past pilot, **don't buy one giant box — run 2+ smaller instances behind Render's load balancer.** Two $85 Pros beat one $175 box *and* survive one instance dying (zero-downtime deploys, no single point of failure). "How many users crash it" is the wrong frame — a crashed instance just gets traffic routed to its twin. **The database (#2) becomes your real ceiling long before the app servers do** — that's why Postgres scales up faster in the table.

### A.3 Vercel (frontend) — "does $20 get everything?"

**Yes — and it essentially can't crash from traffic.** Vercel is serverless/edge: it **auto-scales** to whatever shows up. Your React frontend is static files + light serverless functions; the real work is an API call to Render. So:
- The $20 Pro plan includes a usage credit (bandwidth + function calls). A traffic spike doesn't take you down — it just **spends the credit faster and then bills overage.** You get a bigger invoice, not an outage.
- You'd only feel it as *cost*, not *breakage*, and only at real scale (millions of page views). At pilot/growth, $20 covers it with room to spare.
- **Bottom line:** $20 is genuinely fine. The frontend is almost never the bottleneck because it does no heavy lifting.

### A.4 Expo EAS — "$19, will we hit problems?"

**No — because EAS is not runtime.** Nothing your users do at runtime touches Expo. EAS only **(a) builds the app binary** and **(b) ships over-the-air JS updates.** The installed app talks *directly to Render*. So there's no "crash under load" concept. The only limit that matters:
- **MAU (monthly active users) on OTA updates:** Free = 1,000, **Starter $19 = 3,000**, Production $199 = 50,000.
- If more than 3,000 people open the app in a month, you don't break — you just **bump the plan** so OTA updates keep reaching everyone. Push notifications (Expo/FCM) are free regardless.
- **Bottom line:** $19 is fine until the app has >3k monthly users; then it's a plan bump, never an outage.

### A.5 CloudAMQP / RabbitMQ — what "throughput" and "nodes" mean

RabbitMQ is your event bus (bookings, scans, SLA events between modules). Two separate things you might pay for — and for you, **only one matters:**

- **Throughput (messages/sec) — NOT your constraint.** Each parcel emits ~10–20 events. Even at scale C that's ~150k events/day ≈ **2/sec average, ~50–100/sec at wave peak.** RabbitMQ does *tens of thousands* of messages/sec on a small box. To put your "500 RPS" question in real terms: **500 msg/sec = ~43 million messages/day ≈ what ~3 million parcels/day would generate — about 6× your full-scale target.** A single modest dedicated node eats that without noticing. **You will never buy CloudAMQP for throughput.**
- **High availability (nodes) — THIS is what you pay for.** A single node = if it reboots or dies, you get downtime and can lose in-flight messages. A **3-node cluster** = one node can die and the queue keeps running, plus you get zero-downtime upgrades.

| CloudAMQP plan type | What it is | When |
|---|---|---|
| Shared (Little Lemur, free) | shared node, no HA | dev/throwaway only |
| Single dedicated (~$99, e.g. Tiger) | your own node, real throughput | **pilot** — fine, accept a reboot = brief downtime |
| **3-node HA cluster (~$299+)** | survives a node failure, rolling upgrades | **growth → scale** — this is the "guaranteed availability" you meant |

> **So your instinct is right:** you go multi-node for *availability*, not capacity. At pilot a single dedicated node is plenty; at growth/scale you move to the 3-node HA cluster — and even then throughput is a rounding error against what the hardware can do.

### A.6 One-line summary per service

| Service | Does it "crash" under load? | What you're really buying |
|---|---|---|
| **Render** | Yes — OOM or timeout | raw compute; **scale horizontally (2+ instances) past pilot** |
| **Postgres** | Yes — the real ceiling | RAM/IOPS; your true scaling limit, grows fastest |
| **Vercel** | No — auto-scales | a usage budget; overspend = bill, not outage |
| **Expo EAS** | No — not runtime | build minutes + OTA MAU allowance |
| **CloudAMQP** | Only single-node on reboot | **availability (nodes)**, never throughput at your scale |
