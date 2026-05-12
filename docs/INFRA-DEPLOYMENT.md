# Infrastructure & Deployment Strategy

> **Step-by-step setup instructions are in [`DEPLOYMENT-SETUP.md`](./DEPLOYMENT-SETUP.md).**
> This doc is the summary; that doc is the how-to.

---

## Stack

| Concern | Tool | Notes |
|---------|------|-------|
| Backend hosting | Render | Docker-based; no egress billing |
| Database | Render Managed PostgreSQL | Starter plan, daily backups |
| Kafka | Upstash | Serverless; pay per message; ~$0 at dev/staging volumes |
| OSRM | Self-hosted on Hetzner CX21 | ~$7/month; zero per-call cost; locks out the Railway egress problem |
| UI hosting | Vercel | Free for all 4 React apps; preview per branch auto-generated |
| CI/CD | GitHub Actions | Build + test on every push; free tier covers the team |
| Container registry | GitHub Container Registry (ghcr.io) | Free; used if we pre-build images in CI |

## Environments

| Env | Purpose | Deploy trigger |
|-----|---------|---------------|
| `local` | Branch development | `docker compose up -d` (Postgres + Kafka containers) |
| `preview` | Branch sharing / review | Auto-created by Render on PR open; auto-deleted on merge |
| `staging` | Pre-merge validation | Auto-deploy on merge to `main` |
| `production` | Live traffic | Manual gate after staging passes |

## Branch Testing

No shared dev environment. Each dev runs `docker compose up -d` locally.
When a branch needs to be shared, open a PR — Render auto-spins a preview
environment at `https://1dd-api-pr-<number>.onrender.com` within ~5 min.
No manual steps, no cost until the PR is open.

## CI Pipeline (every push)

```
push → mvn clean install (build + Testcontainers tests) → Docker build (smoke check)
```

## Deploy Pipeline

```
merge to main → Render auto-deploys staging → smoke test
             → manual gate → Render deploys production
```

## Cost Estimate

| Item | Monthly |
|------|---------|
| Render (1dd-api + Postgres, starter plan) | ~$14 |
| Render (preview envs, ephemeral) | ~$5–10 |
| Upstash Kafka | ~$0 at dev/staging volumes |
| Hetzner CX21 (OSRM) | ~$7 |
| Vercel (4 UI apps, all envs) | Free |
| GitHub Actions | Free |
| **Total** | **~$26–31/month** |

## Why Not Railway

Railway bills for egress. M3 and M6 do O(n²) OSRM calls per nightly replan —
that's a lot of outbound requests if OSRM is externally hosted. Self-hosting
OSRM on Hetzner eliminates this entirely, and Render doesn't charge for egress.

## Why Not AWS (yet)

AWS (ECS + RDS + ALB) costs ~$80–150/month minimum and needs 2–3 days of
infra setup. Migrate there when Render becomes a bottleneck or when you need
sub-50ms latency in India (AWS has a Mumbai region). The Docker containers make
the move straightforward.
