# M1 — Auth & Roles · Godspeed Requirements

**Plan sources:** §5 (merchant offering), §7 (channels/plugin), A.3 (D2C beachhead), B.1 (offering), I (channel economics), K (wallet/settlement).
**Module status:** M1 **complete** (174 tests, JWT, 10 roles) — these are *additions/clarifications* the plan implies.

## What the plan demands of identity

The plan's whole demand engine is "a merchant switches Godspeed on at their own checkout in an afternoon" (§7). That makes the **merchant** the first-class principal, reached through two acquisition channels (field sales + self-serve plugin, Annexure I) — so auth must support both a human merchant login and a machine plugin credential.

## Features / requirements

- **R1 — Merchant account principal.** A merchant (D2C brand) is an account with one or more human users and one or more machine credentials. Maps to existing `B2B_USER` + `B2bAccount.ownerUserId`. The "merchant" of the plan == B2B account.
- **R2 — Plugin / API keys (machine-to-machine).** Shopify/WooCommerce installs authenticate as the merchant via a scoped, revocable **API key**, never a human JWT (Annexure I: "near-zero touch" self-serve). Key is scoped to: create shipments, read tracking, read wallet balance — *not* admin/pricing.
- **R3 — Seller-Fulfilled Prime (SFP) seller.** A marketplace seller (Amazon FBM) is the same merchant principal with an attribute flag `sfp_enabled` (Annexure A.3 — "held as upside"). No new role; an entitlement on the merchant account.
- **R4 — Operational roles already in M1 stand.** DA, Van Driver, Hub Operator, Hub–Airport Cron Driver, Station Manager, Supervisor, Call Center Agent, Admin, Airline/GHA read-only — all required by the ground/air execution (§8). No additions.
- **R5 — City-scoped claims** remain mandatory: the 10-city network (E.2) means a Station Manager token is scoped to one node; cross-city actions rejected at service layer.
- **R6 — Wallet/settlement actor.** Prepaid-wallet operations (K.1) are merchant-initiated (top-up) and system/Admin-initiated (debit, weekly settlement). Finance/settlement actions are Admin-gated; merchants may only *read* their ledger and *top up*.
- **R7 — Append-only auth audit** unchanged (role grants, key issue/revoke, SFP toggle all logged).

## Acceptance signals
- A Shopify install can create a shipment and read tracking with **only** an API key, and **cannot** read another merchant's data or change pricing.
- Revoking a merchant's key instantly blocks plugin shipment creation but not in-flight parcels.
- A Station Manager in Bengaluru cannot view/act on a Pune (light-node) queue.

## Open questions
- Q-A1: Do **light-node** cities (Ahmedabad, Pune, Jaipur, Lucknow — delivery-only, 3rd-party last mile) need a distinct ops role, or is it the same Station Manager with reduced entitlements?
- Q-A2: SFP integration may require a marketplace-issued token exchange — out of base case (A.3), flag for Phase 2.
