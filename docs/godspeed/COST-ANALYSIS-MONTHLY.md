# Godspeed — Monthly Platform Cost Analysis

> **Prepared:** August 2026 · **Owner:** Sid · **Scope:** end-to-end recurring cost to *operate* the Godspeed platform (infra, third-party APIs, tooling, dev subscriptions). **Excludes** salaries, ground-ops (DAs/vans/fuel), air freight (AWB), and rent — those are operational COGS, not platform cost.
>
> **FX used:** ₹88 = $1 (Aug 2026). All third-party rates are the *published* 2026 rates cited at the bottom; Indian services (SMS/KYC) priced in ₹, infra/SaaS in $.
>
> **Design bias per instruction:** we size compute for **highest tier / zero-lag**, not for cost-minimisation. Where a cheaper tier is genuinely fine (pilot volumes), it's noted as an option but the headline number assumes the premium tier.

---

## 1. The three volume scenarios

Everything usage-based (Maps, SMS, email, KYC, payment fees) scales with these. Sourced from `docs/godspeed/` (business plan Annexures) and `docs/RUNWAY-JULY-2026.md`.

| Driver | **A · Pilot** (Sept '26) | **B · Early growth** (~Q1 '27) | **C · Scale** (Year-1 target) |
|---|---|---|---|
| Lane coverage | DEL↔BOM only | 10-city, partial lanes lit | 10-city / 90-lane full |
| Parcels / day | ~150 | ~2,000 | ~10,000 |
| Parcels / month | ~4,500 | ~60,000 | ~300,000 |
| OTP logins / month | ~3,600 | ~44,000 | ~200,000 |
| Active DAs | ~20 | ~150 | ~600 |
| KYC onboardings / month | ~30 | ~200 | ~500 |
| Night flights tracked | ~2–4 | ~30 | ~90 |

*(Reference: parent ground network already runs 45k parcels/day; Godspeed air-express is a growing subset of that.)*

---

## 2. Headline — total monthly platform cost

| | **A · Pilot** | **B · Growth** | **C · Scale** |
|---|--:|--:|--:|
| **Total / month (USD)** | **≈ $2,400** | **≈ $7,650** | **≈ $26,000** |
| **Total / month (INR)** | **≈ ₹2.1 L** | **≈ ₹6.7 L** | **≈ ₹22.9 L** |
| *Cost per parcel* | ₹47 | ₹11 | ₹7.6 |

> **Two facts jump out and both are actionable:**
> 1. **At pilot, Claude Max (5 devs) is ~40% of the whole bill** ($1,000 of $2,400). It's fixed — it does not scale with parcels — so per-parcel it vanishes as you grow.
> 2. **At scale, Google Maps is ~58% of the bill** (~$15k of $26k). This is the single biggest lever and the exact reason to move to Ola Maps and/or in-app map SDK. See §5 and §9.
>
> **Not included above** (revenue-linked pass-through COGS, tracked separately in §7): Razorpay/RazorpayX payment fees ≈ $109 / $1,450 / $7,240 per month for A/B/C.

---

## 3. Full line-item breakdown

USD unless marked ₹. Rounded.

| # | Component | Vendor / tier | **A · Pilot** | **B · Growth** | **C · Scale** | Scaling basis |
|---|---|---|--:|--:|--:|---|
| **Compute & hosting** |||||||
| 1 | Backend API + workers | Render — Pro Ultra (32GB/8CPU) ×1→3 + worker + workspace | $700 | $1,950 | $4,200 | HA replicas + DB size |
| 2 | Managed Postgres | Render Postgres (Pro tier) | *(in #1)* | *(in #1)* | *(in #1)* | RAM/storage |
| 3 | Frontends (customer/business/admin) | Vercel Pro — $20/seat ×5 + usage | $100 | $250 | $600 | edge bandwidth |
| 4 | Message broker | CloudAMQP dedicated (Lemur→Ermine) | $99 | $199 | $499 | throughput/AZ |
| 5 | OSRM routing engine | Hetzner Cloud (CCX, self-host) | $60 | $120 | $250 | RAM + HA pair |
| **Customer-facing APIs** |||||||
| 6 | Google Maps (autocomplete + geocode + tracking maps) | Google Maps Platform | $60 | $2,764 | $14,980 | per booking + per tracking view |
| 7 | Flight live-tracking | AeroDataBox / FlightAware AeroAPI | $32 | $160 | $400 | flights/night polled |
| 8 | Map My India (order addresses) | MapmyIndia/Mappls | $20 | $50 | $150 | address lookups |
| **Messaging** |||||||
| 9 | SMS (OTP + alerts) | Airtel IQ / DLT route @ ₹0.15 | ₹3,240 ($37) | ₹42,600 ($484) | ₹210,000 ($2,386) | ~4 SMS/parcel + logins |
| 10 | Email (transactional) | SendGrid Essentials→Pro / AWS SES | $20 | $90 | $150 | ~5 emails/parcel |
| 11 | Push notifications | Expo Push + FCM | $0 | $0 | $0 | free |
| **Identity & compliance** |||||||
| 12 | KYC / verification | sandbox.co.in (PAN+Aadhaar+bank @ ~₹5) | ₹450 ($5) | ₹3,000 ($34) | ₹7,500 ($85) | ~3 checks/onboarding |
| **Observability (was missing — see §6)** |||||||
| 13 | Error tracking (web + mobile) | Sentry Business + RN/Expo | $80 | $200 | $500 | event volume |
| 14 | Logs / APM / uptime | Better Stack / Grafana Cloud | $50 | $150 | $400 | ingest volume |
| **Mobile delivery** |||||||
| 15 | Expo EAS (builds + OTA updates) | EAS Production plan | $99 | $99 | $200 | MAU/bandwidth |
| 16 | App-store accounts | Google Play ($25 once) + Apple ($99/yr) | $10 | $10 | $10 | amortised |
| **Storage & edge** |||||||
| 17 | Object storage + CDN (labels, scans, exports) | AWS S3/GCS + Cloudflare | $20 | $60 | $200 | GB stored/served |
| 18 | Domains / DNS / WAF | Cloudflare Pro + registrar | $20 | $20 | $20 | flat |
| **AI dev tooling** |||||||
| 19 | **Claude Max × 5 devs** | Anthropic Max 20× ($200 ea) | $1,000 | $1,000 | $1,000 | fixed (team size) |
| | **TOTAL / month** | | **≈ $2,412** | **≈ $7,640** | **≈ $26,030** | |
| | **TOTAL / month (₹)** | | **≈ ₹2.12 L** | **≈ ₹6.72 L** | **≈ ₹22.9 L** | |

> **Claude Max note:** $1,000 assumes all 5 devs on **Max 20×**. On **Max 5× ($100)** it's **$500/mo**; a mixed 2×Max20 + 3×Max5 = $700/mo. Pick per how heavily each dev leans on Claude Code.

---

## 4. Your two specific questions, answered

### Q1 — "We display the driver's location on Google Maps but we source the lat/long ourselves (Expo native GPS). Does *displaying* it cost money?"

**Yes — but only on the web, and it's the map canvas you pay for, not the coordinates.**

- Google bills a **"map load"** every time a Maps JavaScript / Dynamic Map renders. **Plotting your own markers on it is free** — you are never charged for the driver's lat/long (you own that data). You're charged for rendering Google's map tiles under your markers.
- **Web (customer tracking page):** billed at **$7 per 1,000 map loads** (after 10k free/month). This is line #6 and the dominant cost at scale.
- **In-app (native Maps SDK for Android/iOS):** **$0 — unlimited free.** Google does *not* charge for dynamic map loads inside a mobile app via the Maps SDK. So if live-tracking is shown inside the **Godspeed app** (React Native maps) rather than the **web** tracking page, it costs **nothing**.
- **Big optimisation:** push as much live-tracking as possible into the app (free SDK), and/or use **Static Maps** ($2/1,000) or Ola Maps on the web tracking page. This alone can cut the Maps bill by more than half at scale.

### Q2 — "We want live flight location. Does a provider exist and what does it cost?"

**Yes, several.** For 90 lanes with a handful of night flights each:

| Provider | Model | Fit |
|---|---|---|
| **AeroDataBox** (RapidAPI) | Free 600 units → Pro $5.35 → Ultra $32 → Mega $160/mo | **Best value**, good India schedule coverage. Recommended for A/B. |
| **FlightAware AeroAPI v3** | Usage-metered, ~$5 free credit, pay-per-query | Most accurate live positions; variable cost. Recommended for C. |
| **AviationStack** | $49.99 (10k calls) → $499.99 (250k)/mo | Fixed buckets, simpler to budget. |

Modeled: **$32 → $160 → $400** across A/B/C (line #7). At pilot (2–4 DEL↔BOM flights/night) AeroDataBox Pro/Ultra is plenty.

---

## 5. Google Maps — why it dominates, and the math

Three customer-facing surfaces, all billed per event after each SKU's 10,000 free/month:

| Surface | SKU | Rate | Events/parcel |
|---|---|---|---|
| Pickup + drop address entry | Places Autocomplete (session) + Details | ~$17 / 1,000 | ~1.2 (saved addresses cut ~40%) |
| Pin → pincode reverse geocode | Geocoding | $5 / 1,000 | ~0.5 |
| Booking map + tracking page views | Dynamic Maps (web) | $7 / 1,000 | ~4 |

**Per-parcel Maps cost ≈ ₹4.5.** Multiplied out:

- **A (4.5k parcels):** mostly inside free tiers → **~$60/mo.**
- **B (60k parcels):** ~$2,764/mo.
- **C (300k parcels):** ~$14,980/mo — **the #1 cost centre.**

**Levers (in priority order):**
1. Move live-tracking maps into the **native app SDK** (free) → removes most of the 4 maploads/parcel.
2. Lean on **saved addresses** (already built) → fewer Autocomplete sessions.
3. Switch web maps to **Ola Maps** (your stated plan) — India-first pricing, materially cheaper; keep Google only where Ola's data is weak.
4. Use **Static Maps** ($2/1k) for the tracking thumbnail, Dynamic only when the user interacts.

---

## 6. Costs we were missing (now included) + future/predicted

Everything below is either already in the table (§3) or flagged here as **near-term additions** you should budget before go-live.

**Already added to §3:** Sentry (error tracking, web + mobile), Better Stack/Grafana (logs/APM/uptime), Expo EAS, app-store accounts, object storage + CDN, WAF/DNS, Map My India.

**Predicted / budget soon (not yet in headline):**

| Item | Why | Rough cost |
|---|---|---|
| **DLT registration** (SMS) | Mandatory for Indian transactional SMS: entity + header IDs | ₹5,900 one-time + ₹590/header/yr |
| **PagerDuty / Opsgenie** on-call | Pilot has real parcels → need paging on RED SLA / outages | $20–40/user/mo |
| **Product analytics** (PostHog/Mixpanel) | Funnel, retention, WISMO measurement (M11 R6) | Free → $200/mo |
| **CI/CD minutes** (GitHub Actions) | Multi-module Maven + RN builds | $0–100/mo |
| **Customer support tooling** (Freshdesk/Zendesk) | WISMO tickets during pilot | $15–50/agent/mo |
| **Secrets / config** (Doppler or cloud KMS) | You already keep Razorpay keys out of git — formalise it | $0–20/mo |
| **DB backups / PITR & DR** | Render includes basic; production wants cross-region snapshots | $20–100/mo |
| **Penetration test / security review** | Before handling real payments + KYC PII at scale | $2–5k one-time, ~annual |
| **Load/soak testing** (k6 Cloud) | Validate "zero-lag" claim under wave bursts | $0–99/mo when running |
| **RazorpayX payouts** | COD settlement to merchants, DA reimbursements | ₹2–5/payout |
| **Apple Developer (iOS)** | Post-pilot; already amortised in #16 | $99/yr |
| **Ola Maps / Mappls contract** | The Maps migration target | TBD, expect << Google at scale |

---

## 7. Payment gateway fees (pass-through COGS — tracked separately)

Not "platform infra," but real cash out the door. Razorpay = **2% + 18% GST = 2.36%** of processed value; COD-to-merchant is flat **₹7/parcel** (~₹2 handling). Assuming ~60% prepaid at ₹150 delivery fee:

| | A · Pilot | B · Growth | C · Scale |
|---|--:|--:|--:|
| Razorpay fees / month | ≈ ₹9,600 ($109) | ≈ ₹1.27 L ($1,448) | ≈ ₹6.37 L ($7,241) |

These are recovered in pricing (M2 already models COD ₹7), so they're revenue-neutral-ish — listed for completeness, **excluded from the §2/§3 platform totals.**

---

## 8. Annual & one-time costs (not monthly)

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

1. **Pilot (now):** the modeled **~$2,400/mo** is dominated by Claude Max ($1,000). Consider **Max 5× for lighter users** → drops the bill to **~$1,900/mo**. Everything else is comfortably in free/entry tiers. You can run the DEL↔BOM pilot on premium compute for **~₹1.7–2.1 L/mo.**
2. **Before growth (B), do the Maps migration.** It's a $0 → $2.8k → $15k trajectory. Moving tracking to the **in-app SDK (free)** + **Ola Maps on web** likely keeps Maps under ~$3–4k even at scale C — a **~$11k/month saving** at 300k parcels.
3. **Add observability now, not later** (Sentry + Better Stack, ~$130/mo at pilot). With real parcels and real payments, flying blind is the expensive option.
4. **Keep Claude Max fixed as headcount, not volume.** It's a flat ~$500–1,000 regardless of parcels; per-parcel it's ₹0.07 at scale — the cheapest leverage in the stack.
5. **Right-size Render honestly.** Pro Ultra (32GB/8CPU) is *enormous* for 150 parcels/day. If pilot latency is fine on a Standard/Pro instance (~$85–225), you save ~$400/mo and still have zero lag. Scale up when B arrives.

---

## Sources (2026 published rates)

- Google Maps Platform pricing — [woosmap.com](https://www.woosmap.com/blog/google-maps-api-pricing-breakdown), [mapatlas.eu](https://mapatlas.eu/blog/google-maps-api-pricing-2026)
- Indian SMS/OTP + DLT — [messagecentral.com](https://www.messagecentral.com/blog/sms-otp-pricing-india), [Airtel DLT](https://dltconnect.airtel.in/faq/)
- sandbox.co.in KYC — [sandbox.co.in/pricing](https://sandbox.co.in/pricing)
- Claude Max — [claude.com/pricing](https://claude.com/pricing)
- Render — [render.com pricing](https://costbench.com/software/developer-tools/render/)
- Vercel — [costbench.com/vercel](https://costbench.com/software/developer-tools/vercel/)
- CloudAMQP — [cloudamqp.com/plans](https://www.cloudamqp.com/plans.html)
- FlightAware AeroAPI / AeroDataBox / AviationStack — [aerodatabox.com/pricing](https://aerodatabox.com/pricing/), [flightaware.com/commercial/aeroapi](https://www.flightaware.com/commercial/aeroapi/)
- Sentry — [middleware.io/sentry-pricing](https://middleware.io/blog/sentry-pricing/)
- Expo EAS — [docs.expo.dev/billing/plans](https://docs.expo.dev/billing/plans/)
- SendGrid — [sendx.io/sendgrid-pricing](https://www.sendx.io/blog/sendgrid-pricing)
- Razorpay — [razorpay.com](https://razorpay.com/blog/razorpay-payment-gateway-pricing-explained/)
