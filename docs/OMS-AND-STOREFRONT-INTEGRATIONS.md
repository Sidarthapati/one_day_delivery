# Godspeed — Go-Live Credentials, Notifications, and the OMS + Storefront Integration Plan

Status: planning doc (no code). Author-of-record: this captures the effort, the process, what
_you_ must sign up for, and what changes in _our_ codebase. Nothing here is built yet.

---

## Part 0 — TL;DR

1. **Credentials still outstanding** are mostly "flip a provider from log/mock to live + give keys":
   Razorpay **live**, RazorpayX (payouts), an SMS/DLT provider, an email provider (SendGrid/SES),
   live KYC keys (+ Aadhaar). None require code beyond config; a couple need a small adapter.
2. **Email/SMS already have a framework.** Email → SendGrid adapter is wired; SMS → Msg91 is wired.
   Both default to a **log sink** today. "Linking Twilio" means: Twilio-SendGrid for email (already
   compatible), and for SMS either a small Twilio adapter or use a native DLT provider. The real work
   is **DLT registration** (SMS) and **domain authentication** (email), not code.
3. **OMS + Shopify/WooCommerce is the big one.** We already have the hard parts (API keys, signed
   webhooks, serviceability, quoting, SKU-less booking). The new work is a **connector module** on
   our side plus **one app per platform** (a Shopify app, a WooCommerce plugin). Rough sizing:
   **backend OMS core ~2–3 wks, Shopify app ~3–4 wks (+ review), WooCommerce plugin ~2–3 wks.**
   Sequence: backend first, then Shopify, then Woo.

---

## Part 1 — Credentials & accounts still required

Everything below is gated by a config switch that defaults to a safe stub, so the platform runs
today without any of it. "Live" means giving real keys + flipping the switch.

| Capability | Today (default) | To go live you provide | Where you get it | Code work |
|---|---|---|---|---|
| **Payments** (prepaid, wallet recharge) | Razorpay **test** keys (`rzp_test_…`), `RAZORPAY_LIVE=true` | `rzp_live_…` key id + secret; optionally a Razorpay **webhook** secret | Razorpay dashboard after full business KYC | None (env only) |
| **Payouts** (COD remittance to merchants) | `payout.provider=manual` (you transfer + record UTR) | RazorpayX key/secret + a **funded RazorpayX current account**; merchant fund-accounts | RazorpayX (separate product from Payments; own KYC) | None — adapter exists (`payout.provider=razorpayx`) |
| **SMS** (OTP, milestones) | `notify.sms.provider=log` | Provider API key + **DLT** sender-ID + template IDs + PE (principal entity) ID | Msg91 / Knostics / Twilio + the DLT portal (Jio/Airtel/Vi) | Msg91 wired; **Twilio needs a small adapter** |
| **Email** (invoices, milestones, onboarding) | `notify.email.provider=log` | SendGrid API key **or** SES access key/secret + region; a **verified from-domain** | SendGrid (Twilio-owned) / AWS SES | None — SendGrid adapter exists (`=sendgrid`); SES would need a small adapter |
| **KYC/KYB** (GSTIN/PAN, later Aadhaar) | Sandbox.co.in **mock** (`kyc.live=false`) | `KYC_API_KEY` / `KYC_API_SECRET`, `KYC_LIVE=true` | Sandbox.co.in (or equivalent); Aadhaar needs a provider that supports offline/OTP eKYC + a consent screen | None for GST/PAN; **Aadhaar is a new flow** (consent + provider call) |
| **Maps** | Live (Google Places + Maps JS) | Already done. Ensure **billing account** + quotas; MapMyIndia only if you want it for address parsing | Google Cloud | Done |
| **Routing (M6)** | OSRM self-host (Hetzner) | Keep the OSRM server up with India OSM extract | Self-hosted | Infra, not a key |
| **Broker / DB** | CloudAMQP + Render Postgres (staging) | For real prod: a dedicated prod DB + broker, and a secrets store | Render / CloudAMQP | Config/infra |

**Net:** the only items that need *any* code are (a) a **Twilio SMS adapter** if you go Twilio for
SMS, (b) an **SES adapter** if you prefer SES over SendGrid, and (c) the **Aadhaar** KYC flow. All
the rest is "give keys, flip the switch."

---

## Part 2 — Notifications: where we are and what "checking it" means

We have a proper notification layer already:

- `Notifier` fires on key **shipment milestones** (booked, picked up, in transit, out for delivery,
  delivered) and on **COD remittance paid**, plus **onboarding approve/reject** and **OTP**.
- `SmsSender` and `EmailSender` are ports. Defaults log to the app console; real providers swap in by
  config (`notify.sms.provider`, `notify.email.provider`).

To **verify email end-to-end** we need to flip email to a real provider, because until then nothing
leaves the server (it just logs). Steps:

1. Pick **SendGrid** (fastest; Twilio-owned, adapter already wired) or **SES**.
2. **Authenticate a from-domain** — add the SPF/DKIM (and ideally DMARC) DNS records the provider
   gives you. This is the part that actually determines whether mail lands in the inbox vs. spam.
3. Set the API key + from-address, set `notify.email.provider=sendgrid`, redeploy.
4. Trigger a milestone (e.g., approve an onboarding, or book a shipment) and confirm receipt.

For **SMS/Twilio** specifically: Twilio can deliver to India, but Indian transactional SMS is gated
by **DLT** — you must register your entity (PE ID), your **sender ID** (header), and every
**message template** on the DLT portal, then map those template IDs into the provider. Twilio needs
those DLT IDs attached to the sender. Native providers (Msg91/Knostics) tend to make DLT smoother
because they're built around it. Either way, the blocker is **DLT registration, not code** — and if
you go Twilio, we add a ~half-day adapter mirroring the Msg91 one.

**Recommendation:** SendGrid for email now (adapter ready), and for SMS use whichever of
Msg91/Knostics your CTO already has a DLT account on — fewer approvals to chase. Add Twilio later if
you want a single vendor.

---

## Part 3 — The OMS + Storefront vision (restated)

You want Godspeed to behave as an **OMS + carrier**, not just a booking form:

1. **Order lands on the store** (Shopify/WooCommerce) → it **shows up in Godspeed** automatically.
2. Merchant clicks **"Ready to collect"** → we **book the delivery**, paid from the **wallet**.
3. **At the store's checkout**, if the origin city (merchant warehouse) and the destination city
   (buyer) are **both serviceable**, show **"Godspeed — guaranteed one-day delivery"** as a shipping
   option, with a live price.
4. **SKU-less:** we don't need the merchant's product catalog. To book we only need **pickup, drop,
   package dimensions/weight, declared value, COD flag/amount, and the price** — which is exactly how
   our B2B booking already works.

There are **two distinct integration surfaces**, and it's important not to conflate them:

- **A. Order ingestion (the "OMS" half).** The store tells us about orders (via webhooks/API). We
  store them as *pending fulfilments* and let the merchant convert them into booked shipments.
- **B. Checkout rate (the "carrier" half).** The store asks *us*, in real time during checkout,
  whether we serve this route and for how much. This is a **carrier/shipping-method** integration and
  is technically separate from order ingestion.

Plus a third, quieter surface:

- **C. Status write-back.** As our shipment moves, we push tracking + status back onto the store's
  order (creates a fulfilment, adds a tracking number, marks delivered). This reuses our existing
  event/webhook lane.

---

## Part 4 — What we already have (so this is smaller than it looks)

- **Per-merchant API keys** (`X-Api-Key`) and **HMAC-signed outbound webhooks**
  (`X-Godspeed-Signature`) with a delivery log — the exact primitives a store connector needs.
- **Serviceability** (`/api/grid/serviceable-at`) and **quoting** (pricing engine, city-pair rates).
- **B2B booking** (single + bulk), **wallet** funding, **white-label tracking**.
- **SKU-less booking** is native: the booking request is addresses + dims + value + price. No catalog.

So the connector mostly **maps** a store order onto things we can already do.

---

## Part 5 — Backend changes on our side (new module: **M12 "Integrations / OMS"**)

New, self-contained module. Rough shape:

1. **External-order model** — `external_order` (source platform, shop/site id, external order id,
   buyer contact + address, optional line items, COD amount, status, linked `shipment_ref`).
   **Idempotent** on (platform, external id) so retries/duplicate webhooks don't double-create.
2. **Connector credentials** — per-merchant store auth (Shopify shop domain + offline access token;
   Woo site URL + REST keys), encrypted at rest. Shopify requires an **OAuth install** flow.
3. **Inbound webhook receivers** — one endpoint per platform that **verifies the platform's own HMAC**
   (Shopify/Woo each sign their webhooks), normalises the payload, and upserts an `external_order`.
4. **Rate endpoint** — a fast, public "is this route serviceable + what's the price" endpoint tuned
   for carrier-service calls (must answer in a couple of seconds). Thin wrapper over serviceability +
   quote.
5. **"Ready → Book"** — converts an `external_order` into a real shipment via the existing B2B
   booking service (wallet debit), requiring only dims + confirmed price. Can be a merchant click or,
   later, automatic on a store status.
6. **Status write-back** — on shipment transitions, call the store's API to create/​update the
   fulfilment + tracking. Hooks into the same event lane the webhooks already use.
7. **Merchant OMS screen** in the business portal — an "Orders" inbox listing incoming store orders
   with a **Mark ready → confirm dims/price → Book (wallet)** action, and a status column that reflects
   both the store and our shipment.

The **Shopify app UI** (embedded, Polaris) and the **WooCommerce plugin** (PHP) live *outside* the
current Java/Next repos — they're separate deliverables (see below).

---

## Part 6 — Shopify

**App model.** You build a **Shopify app**. Two routes:

- **Custom app** (installed on specific merchant stores you control): fastest, no marketplace review,
  fine for a pilot and for your own/partner merchants.
- **Public app** (listed on the Shopify App Store): broad reach, but requires **business
  verification + app review** and ongoing compliance. Do this *after* the model is proven.

**What you onboard / sign up for:**

- A **Shopify Partners** account (free) → create the app → set OAuth redirect + request scopes
  (`read_orders`, `write_fulfillments`/`write_merchant_managed_fulfillment_orders`,
  `read_shipping`, etc.).
- A **development store** to test against.
- For the **checkout rate** feature: the merchant's store must support **carrier-calculated
  shipping** — historically **Advanced Shopify / Plus**, or any plan billed **annually**, or the
  paid **carrier-calculated-shipping add-on**. **Verify current gating with Shopify** — this is the
  one real external constraint on the "show us at checkout" feature.

**The three surfaces on Shopify:**

- **Order ingestion:** subscribe to `orders/create` / `orders/paid` webhooks; read details via the
  Admin API (GraphQL).
- **Checkout rate:** register a **CarrierService**; Shopify POSTs the cart (origin, destination,
  items) to our callback; we return the Godspeed rate (or nothing, if unserviceable). Must be fast.
- **Write-back:** use the **FulfillmentOrders / Fulfillment** APIs to mark fulfilled and attach the
  tracking number/URL (deep-links to our white-label tracking).

**Auth:** OAuth 2.0 per shop; store the offline access token.

**Effort:** ~**3–4 weeks** for OAuth + webhooks + CarrierService + fulfilment write-back + a minimal
embedded settings screen, **plus Shopify review lead time** if you go public.

---

## Part 7 — WooCommerce

**App model.** WooCommerce is WordPress + PHP, so you ship a **plugin** the merchant installs on
their site. Two parts inside the plugin:

- **Shipping method** — a `WC_Shipping_Method` that, at cart/checkout, calls our **rate endpoint**;
  if the route is serviceable it adds **"Godspeed — One-Day"** with the live price.
- **Order sync** — on order creation/status change, the plugin **pushes the order to Godspeed**
  (using the merchant's Godspeed API key stored in plugin settings), and **writes our tracking back**
  onto the WooCommerce order when we notify it.

**What you onboard / sign up for:**

- Nothing platform-gated like Shopify's carrier plan — any WooCommerce store can add a custom
  shipping method. 
- To **distribute**: either publish on the **WordPress.org plugin directory** (free, but a review),
  or hand merchants a signed `.zip` to upload. The merchant installs it and pastes their **Godspeed
  API key** into the plugin settings.
- Auth is symmetric: our key lets the plugin call us; a **WooCommerce REST API key** (consumer
  key/secret) lets us read/write their orders if we sync server-to-server instead of in-plugin.

**Effort:** ~**2–3 weeks** for the shipping method + order sync + tracking write-back + a settings
screen, plus directory-review lead time if you list it.

---

## Part 8 — SKU-less "base carrier" mode

This is already how we work and is worth stating explicitly because it's a selling point:

- We are a **carrier + OMS**, not a catalog system. A booking needs **pickup, drop, weight/dims,
  declared value, COD flag/amount, and the price** — never a SKU.
- The connector **maps** the store order's shipping address → drop, the merchant's warehouse → pickup,
  and asks the merchant (or a saved default) for **dims** at "Ready to collect." Price comes from our
  quote. If the store *does* send weights/dims, we prefill them; if not, the merchant enters them
  once (and can save a default package profile).
- This means a merchant with a messy or absent product catalog can still ship through us on day one.

---

## Part 9 — Effort, sequencing, and dependencies

**Suggested order (each builds on the last):**

1. **Backend M12 core** (external-order model, ingestion webhooks, rate endpoint, book-from-order,
   write-back, portal Orders inbox) — ~**2–3 weeks**. Delivers value even before any app: you can
   accept orders via API and the merchant OMS screen works.
2. **Shopify app** — ~**3–4 weeks** + review. Highest-leverage marketplace.
3. **WooCommerce plugin** — ~**2–3 weeks** + review.

**Runs in parallel with go-live creds:** payments-live, payouts (RazorpayX), SMS/DLT, email/SendGrid,
Aadhaar. None of these block the integration build; they block *production launch*.

**Hard external dependencies to start now (long lead times):**

- Razorpay **live** + RazorpayX business KYC.
- **DLT** entity + template registration (can take days–weeks).
- Email **domain authentication**.
- Shopify **Partners** account + (for checkout rates) confirming the merchant plan gating.

---

## Part 10 — What you (the human) need to action

- [ ] Complete **Razorpay live** KYC → live keys; open **RazorpayX** → keys + funded account.
- [ ] Choose an **SMS provider** (Msg91/Knostics/Twilio) and complete **DLT** registration (PE ID,
      sender ID, templates).
- [ ] Choose **email** (SendGrid vs SES) and **authenticate a sending domain** (DNS records).
- [ ] Get **live KYC** keys; decide whether Aadhaar is in scope for launch (extra flow).
- [ ] Create a **Shopify Partners** account; decide **public vs custom** app; confirm **carrier-rate
      plan gating** for your target merchants.
- [ ] Decide **WooCommerce** distribution (wp.org listing vs. direct `.zip`).
- [ ] Decide launch scope: **OMS-only first** (orders sync + book from portal) vs. **OMS + checkout
      rate** on day one.

## Part 11 — Open decisions for us

- Public Shopify app (marketplace, review, reach) vs. custom app (per-merchant, faster). Recommend
  **custom first** for the pilot, public later.
- Auto-book on a store status vs. always merchant-confirmed "Ready to collect." Recommend
  **merchant-confirmed** first (dims/price are a human check), auto later.
- One package-profile per merchant (default dims) to keep "Ready → Book" one click.
- Whether write-back tracking points at our **white-label** page (recommended) or a generic one.
