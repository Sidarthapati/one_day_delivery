# Godspeed — Data Protection (DPDP) Map & Retention Policy

Maps the personal data we process to DPDP Act (India) obligations, and defines retention/deletion.
Living doc — the legal review + Grievance Officer appointment happen at entity formation; the data
map and retention rules below can be implemented now.

## 1. Personal data inventory

| Data | Where | Purpose | Sensitivity |
|------|-------|---------|-------------|
| Customer name, phone, email | `users`, `onboarding_requests` | Auth, contact, notifications | PII |
| Pickup/drop addresses + lat/lon | `shipments` (Address embedded) | Fulfilment, routing | PII (location) |
| Recipient name/phone | `shipments` | Delivery, OTP exchange | PII (third-party) |
| Payment references (Razorpay ids) | `payment_transactions` | Payment, refunds, recon | Financial (no PAN stored) |
| B2B KYC (GSTIN, bank) | `b2b_accounts`, KYC calls | Onboarding, payouts | Financial/regulated |
| DA/agent identity + live GPS | `da_gps_ping`, `*_live_status` | Dispatch, live tracking | PII (location, staff) |
| Pickup/delivery OTPs (BCrypt) | `pickup_otp` etc. | Custody handoff | Secret (hashed) |
| API keys (SHA-256) | `api_keys` | B2B API auth | Secret (hashed) |
| Audit/event logs | Axiom, `AuditLog` | Security, debugging, dispute | PII-adjacent |

## 2. DPDP obligations → status

| Obligation | How we meet it | Status |
|------------|----------------|:------:|
| Lawful purpose + notice | Consent at signup/booking; purpose limited to fulfilment | 🟠 notice copy at entity |
| Data minimisation | Only fulfilment-necessary fields; no PAN/card stored (Razorpay tokenised) | 🟢 |
| Security safeguards | TLS in transit; Postgres encryption at rest; OTP/API-key/password hashed; authz + rate limiting | 🟢 (prod profile) |
| Breach notification | Incident runbook (`RUNBOOKS.md §3`); Board-notification workflow | 🟠 template |
| Retention limitation | Section 3 below | 🟢 policy / 🟠 job |
| Rights (access/correction/erasure) | Address book edit; erasure workflow | 🟠 build erasure endpoint |
| Grievance Officer | Named contact + `security.txt` | 🟠 appoint at entity |
| Processor agreements | Razorpay, Render, Vercel, CloudAMQP, Axiom, SMS/email DPAs | 🟠 sign at entity |

## 3. Retention & deletion

| Data class | Retain | Then |
|------------|--------|------|
| Shipment + address records | 7 years (tax/dispute) | Anonymise recipient PII, keep aggregates |
| Payment transactions | 8 years (financial law) | Retain refs; drop any incidental PII |
| OTPs | Until leg complete (TTL 120m pickup) | Hard-delete (already TTL/expiry) |
| Idempotency keys | 24h | Purged by `IdempotencyKeyPurgeJob` |
| DA/agent GPS pings | 90 days | Delete raw; keep aggregate ops metrics |
| Auth/audit logs (Axiom) | 1 year hot, then archive | Delete/anonymise after archive window |
| Closed/deleted accounts | 30 days grace | Erasure: anonymise PII, keep legally-required records |

**To implement now:** a scheduled retention job (extend the `IdempotencyKeyPurgeJob` pattern) for GPS
pings (90d) and OTPs, plus an account-erasure endpoint that anonymises PII while preserving
tax/financial records. Tracked as a follow-up (Branch 2 / go-live).

## 4. Access to personal data (need-to-know)

- App-level RBAC (M1's 10 actor roles + city scoping) already gates who sees what in the product.
- Human/infra access (who on the team can query the prod DB, read Axiom, etc.) is least-privilege per
  role and is set up at team formation — see `PROD-READINESS-NOW.md §11` (the orange "team RBAC" item).
