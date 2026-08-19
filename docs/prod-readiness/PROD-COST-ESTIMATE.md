# Godspeed — Production Cost Estimate (pilot)

Rough monthly run-rate for a **small pilot** (DEL↔BOM, low volume). Figures are planning estimates in
USD (list prices, ~₹84/$); confirm against live dashboards before committing. Free tiers are used
where they're genuinely sufficient for pilot load.

## Monthly estimate

| Component | Plan | ~USD/mo | Notes |
|-----------|------|--------:|-------|
| Backend compute (Render) | Web service, Starter/Standard | $7–25 | Single Docker instance; bump for headroom |
| Postgres (Render) | Managed, small | $7–20 | Daily backups; larger for retention/PITR |
| RabbitMQ (CloudAMQP) | Free (Lemur) → Tiger | $0–19 | Free tier OK for pilot; paid for SLA/metrics |
| Frontends (Vercel) | Hobby → Pro | $0–20/seat | 6 consoles, one project each; Pro for team/SSO |
| OSRM (Hetzner) | Small VPS | $5–10 | Self-hosted routing; India extract |
| Maps/geocoding | Pay-as-you-go | $0–50 | Quota-capped; watch usage |
| Error tracking (Sentry) | Developer/free | $0–26 | Free tier for pilot volume |
| Metrics (Grafana Cloud) | Free | $0 | Scrapes `/actuator/prometheus` |
| Logs (Axiom) | Free | $0 | Free tier covers pilot log volume |
| Uptime monitor | Free | $0 | Better Stack / UptimeRobot |
| **Subtotal (infra)** | | **~$26–190/mo** | Lower end = free tiers; upper = paid SLAs |

## Usage-based (scale with volume, not fixed)

| Service | Model | Trigger to watch |
|---------|-------|------------------|
| Razorpay | ~2% + GST per txn | Scales with GMV; not a fixed cost |
| SMS (DLT) | per SMS | OTP + notifications volume |
| Email (SendGrid) | free tier → per email | Notification volume |
| KYC (GSTIN/bank verify) | per verification | B2B onboarding count |

## Notes

- **Entity-blocked (not in the run-rate above):** live Razorpay/KYC/DLT-SMS require the company —
  they stay sandbox mocks until then (`PROD-READINESS-NOW.md` `[ENTITY]` table). No spend yet.
- **Scaling levers:** Render instance size + count, Postgres tier, CloudAMQP tier. Right-size these
  from the Branch 2 k6 load test rather than guessing.
- **Human pentest** (₹5–6L one-off) is deferred; automated OWASP ZAP (Branch 2) covers the pilot.
- Pilot realistic floor if you lean on free tiers: **~$25–40/mo** infra + usage-based on top.
