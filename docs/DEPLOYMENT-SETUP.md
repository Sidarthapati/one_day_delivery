# Deployment Setup Guide

Everything you need to go from "placeholder code in a git repo" to a working
multi-environment deployment where any developer can push a branch and get a
live test URL.

---

## Architecture at a glance

```
GitHub repo
  │
  ├─ push to any branch / open PR
  │       │
  │       ├─► GitHub Actions CI  (build + test, blocks merge if failing)
  │       └─► Render preview env (auto-spun, auto-torn-down on merge)
  │               - 1dd-api  (Spring Boot JAR)
  │               - Postgres  (clone of staging DB schema)
  │
  ├─ merge to main
  │       └─► Render staging (auto-deploy)
  │
  └─ manual gate
          └─► Render production

UIs (4 separate repos or sub-directories, connected to Vercel)
  └─► Vercel auto-creates preview URL per branch, prod URL on main

Shared infrastructure (one-time, not per-env)
  ├─ Upstash Kafka  (serverless; free at dev/staging volumes)
  └─ Hetzner VPS   (OSRM — self-hosted, zero per-call cost)
```

**Total monthly cost at this stage: ~$40–55**

---

## Part 1 — Create accounts (one-time, ~30 min)

Do these in order. Keep all credentials in a password manager; you'll add them
to GitHub Secrets in Part 6.

### 1.1 GitHub
Already have it. Make sure the repo is on GitHub.

### 1.2 Render
1. Go to https://render.com → Sign up with GitHub.
2. Authorise Render to access your GitHub org/account.
3. No credit card needed to start; add one before you go to staging (free tier
   has sleep-on-idle which you don't want for a backend).

### 1.3 Upstash
1. Go to https://upstash.com → Sign up (GitHub SSO works).
2. Navigate to **Kafka** → **Create Cluster**.
3. Name: `1dd-kafka`  |  Region: `ap-south-1` (Mumbai) or `eu-west-1`
   (closer latency for now — Mumbai Kafka isn't always available on free tier,
   pick whichever is).
4. Keep **Single Zone** (free). Multi-zone is for production later.
5. After creation, click the cluster → **Details** tab. Copy and save:
   - **Bootstrap server** (`*.upstash.io:9092`)
   - **Username**
   - **Password**
6. Go to **Topics** → create one topic to confirm it works: `test-topic`.
   Delete it after. You'll create real topics from code later.

### 1.4 Hetzner (OSRM server)
1. Go to https://hetzner.com → Sign up, add a credit card.
2. Go to **Cloud** → **New Project** → name it `1dd-infra`.
3. Do NOT create the server yet — you'll do that in Part 5.

### 1.5 Vercel
1. Go to https://vercel.com → Sign up with GitHub.
2. Authorise Vercel to access your GitHub org.
3. Do NOT create projects yet — you'll do that in Part 7 once you have UI
   directories in the repo.

---

## Part 2 — Add files to the repo (~45 min)

All files below go in the root of the `one_day_delivery` repo unless a path
is specified.

### 2.1 `Dockerfile`

This builds the single runnable `app/` fat JAR.

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Copy every module's pom.xml first so Docker layer-caches dependency downloads
COPY pom.xml .
COPY common/pom.xml      common/pom.xml
COPY auth/pom.xml        auth/pom.xml
COPY pricing/pom.xml     pricing/pom.xml
COPY grid/pom.xml        grid/pom.xml
COPY barcode/pom.xml     barcode/pom.xml
COPY orders/pom.xml      orders/pom.xml
COPY dispatch/pom.xml    dispatch/pom.xml
COPY routing/pom.xml     routing/pom.xml
COPY hub/pom.xml         hub/pom.xml
COPY airline/pom.xml     airline/pom.xml
COPY sla/pom.xml         sla/pom.xml
COPY exceptions/pom.xml  exceptions/pom.xml
COPY app/pom.xml         app/pom.xml
RUN mvn dependency:go-offline -q

# Copy source and build
COPY . .
RUN mvn clean package -DskipTests -pl app --also-make -q

# Stage 2: runtime (tiny JRE-only image)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 2.2 `.dockerignore`

```
.git
.github
target
*/target
*.md
docs/
diagrams/
```

### 2.3 `docker-compose.yml` (local dev only)

Developers run `docker compose up -d` to get a local Postgres + Kafka. The
Spring Boot app itself runs from IntelliJ/terminal, NOT inside Docker locally
(faster iteration).

```yaml
version: '3.9'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: oneday_dev
      POSTGRES_USER: oneday
      POSTGRES_PASSWORD: oneday
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U oneday"]
      interval: 5s
      retries: 5

  kafka:
    image: bitnami/kafka:3.7
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      ALLOW_PLAINTEXT_LISTENER: "yes"

volumes:
  pgdata:
```

Start: `docker compose up -d`
Stop:  `docker compose down`
Wipe DB: `docker compose down -v`

### 2.4 `.env.example`

Copy this to `.env` locally (`.env` is gitignored). Each developer fills in
their local values. Staging/prod values live in Render's dashboard, not here.

```bash
# ── Local Postgres ──────────────────────────────────────────────────────────
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/oneday_dev
SPRING_DATASOURCE_USERNAME=oneday
SPRING_DATASOURCE_PASSWORD=oneday

# ── Kafka (local docker-compose) ─────────────────────────────────────────────
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
# For staging/prod Upstash, set these instead:
# KAFKA_BOOTSTRAP_SERVERS=your-cluster.upstash.io:9092
# KAFKA_SASL_JAAS_USERNAME=upstash-username
# KAFKA_SASL_JAAS_PASSWORD=upstash-password

# ── OSRM ─────────────────────────────────────────────────────────────────────
OSRM_BASE_URL=http://localhost:5000
# For staging/prod Hetzner: http://<hetzner-ip>:5000

# ── Auth ─────────────────────────────────────────────────────────────────────
JWT_SECRET=local-dev-secret-change-in-prod-minimum-32-chars

# ── Spring profile ───────────────────────────────────────────────────────────
SPRING_PROFILES_ACTIVE=local
```

Add `.env` to `.gitignore` if it isn't already:
```bash
echo ".env" >> .gitignore
```

### 2.5 `render.yaml` (Render Blueprint — IaC for Render)

This single file defines all your Render services. Render reads it when you
import the repo. This means your infra is code-reviewed alongside your code.

```yaml
services:
  - type: web
    name: 1dd-api
    runtime: docker
    dockerfilePath: ./Dockerfile
    plan: starter          # $7/month, no sleep-on-idle
    region: singapore      # closest to India right now
    healthCheckPath: /actuator/health
    autoDeploy: true

    # Preview environments: Render auto-clones this service for every PR
    previewsEnabled: true
    previewPlan: starter

    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: render

      - key: SPRING_DATASOURCE_URL
        fromDatabase:
          name: 1dd-postgres
          property: connectionString

      - key: SPRING_DATASOURCE_USERNAME
        fromDatabase:
          name: 1dd-postgres
          property: user

      - key: SPRING_DATASOURCE_PASSWORD
        fromDatabase:
          name: 1dd-postgres
          property: password

      # These you'll fill in manually in the Render dashboard (see Part 4)
      - key: KAFKA_BOOTSTRAP_SERVERS
        sync: false
      - key: KAFKA_SASL_JAAS_USERNAME
        sync: false
      - key: KAFKA_SASL_JAAS_PASSWORD
        sync: false
      - key: OSRM_BASE_URL
        sync: false
      - key: JWT_SECRET
        generateValue: true   # Render auto-generates a random value

databases:
  - name: 1dd-postgres
    databaseName: oneday
    user: oneday
    plan: starter            # $7/month, 1GB storage, daily backups
    region: singapore
```

### 2.6 `.github/workflows/ci.yml`

Runs on every push and every PR. Blocks the merge if tests fail.

```yaml
name: CI

on:
  push:
    branches: ["**"]
  pull_request:

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    timeout-minutes: 20

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: "21"
          distribution: temurin
          cache: maven          # caches ~/.m2 between runs

      - name: Build and run tests
        run: mvn clean install --batch-mode

      - name: Build Docker image (smoke check only)
        run: docker build -t 1dd-api:ci .
```

> **Note on Testcontainers**: when you add integration tests using
> `@SpringBootTest` + Testcontainers, they spin up real Postgres/Kafka
> containers inside the CI runner. No extra setup needed — GitHub Actions
> runners have Docker available.

---

## Part 3 — Spring Boot config for Render profile

When `SPRING_PROFILES_ACTIVE=render`, Spring Boot picks up
`application-render.yml`. Create this file at:
`app/src/main/resources/application-render.yml`

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    hikari:
      maximum-pool-size: 5      # Render starter Postgres allows 25 connections

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: SCRAM-SHA-256
      sasl.jaas.config: >
        org.apache.kafka.common.security.scram.ScramLoginModule required
        username="${KAFKA_SASL_JAAS_USERNAME}"
        password="${KAFKA_SASL_JAAS_PASSWORD}";

  jpa:
    hibernate:
      ddl-auto: validate        # never auto-create in cloud envs
    show-sql: false

osrm:
  base-url: ${OSRM_BASE_URL}

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

And the local profile at `app/src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/oneday_dev}
    username: ${SPRING_DATASOURCE_USERNAME:oneday}
    password: ${SPRING_DATASOURCE_PASSWORD:oneday}

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

  jpa:
    hibernate:
      ddl-auto: update          # OK locally; never in cloud
    show-sql: true

osrm:
  base-url: ${OSRM_BASE_URL:http://localhost:5000}
```

---

## Part 4 — Set up Render (~30 min)

### 4.1 Connect the repo

1. Render dashboard → **New** → **Blueprint**.
2. Connect your GitHub account → select the `one_day_delivery` repo.
3. Render reads `render.yaml` and shows you a preview of what it will create:
   - Service: `1dd-api`
   - Database: `1dd-postgres`
4. Click **Apply**. Render provisions the Postgres DB first, then builds and
   deploys the Docker image. First build takes ~8–10 min (Maven download).

### 4.2 Set the secrets Render can't generate automatically

After the Blueprint applies:
1. Go to **Services** → `1dd-api` → **Environment**.
2. Fill in the values marked `sync: false` in `render.yaml`:

| Key | Value | Where to get it |
|-----|-------|-----------------|
| `KAFKA_BOOTSTRAP_SERVERS` | `your-cluster.upstash.io:9092` | Upstash dashboard |
| `KAFKA_SASL_JAAS_USERNAME` | Upstash username | Upstash dashboard |
| `KAFKA_SASL_JAAS_PASSWORD` | Upstash password | Upstash dashboard |
| `OSRM_BASE_URL` | `http://<hetzner-ip>:5000` | After Part 5 |

3. Click **Save Changes** → Render redeploys.
4. Verify: hit `https://1dd-api.onrender.com/actuator/health` → should return
   `{"status":"UP"}`.

### 4.3 How preview environments work (this is the branch-deploy feature)

Render preview environments are automatic once you enable them (already done
in `render.yaml` via `previewsEnabled: true`).

**Developer workflow:**
```
git checkout -b feature/grid-replan
# ... make changes ...
git push origin feature/grid-replan
# Open a PR on GitHub

→ Render detects the PR and within ~5 min spins up:
    https://1dd-api-pr-<number>.onrender.com

→ That URL has its own isolated Postgres (schema-only clone of staging)
  and points to the same shared Upstash Kafka + Hetzner OSRM.

→ Developer shares the URL with the team for review.
→ PR merges → Render auto-tears down the preview env.
```

No manual steps. No risk of stepping on another dev's env.

### 4.4 Create staging and production as separate services (optional but recommended)

`render.yaml` above creates one service that Render uses for both staging and
production via its deploy pipeline. If you want explicit separate services:

1. Duplicate the service in Render: **Services** → `1dd-api` → **Settings** →
   scroll to bottom → **Duplicate**.
2. Name the duplicate `1dd-api-prod`.
3. Set `autoDeploy: false` on the prod service (manual gate only).
4. In GitHub Actions (or Render's deploy hooks), trigger prod only after staging
   smoke tests pass.

For now, one service (staging) is enough. Add prod separation before you have
real users.

---

## Part 5 — Hetzner OSRM setup (~60 min)

This eliminates the "external call cost" problem. OSRM runs on your own server;
no per-request billing ever.

### 5.1 Provision the server

1. Hetzner Cloud → project `1dd-infra` → **Add Server**.
2. Settings:
   - Location: **Bangalore** (BLR) if available, else **Singapore**.
   - Image: **Ubuntu 24.04**.
   - Type: **CX21** (2 vCPU, 4 GB RAM) — sufficient for India OSM + matrix API.
   - SSH key: paste your public key (`~/.ssh/id_ed25519.pub` or generate one).
   - Name: `1dd-osrm`.
3. Click **Create & Buy** (~€4–7/month).
4. Note the server's public IP.

### 5.2 SSH in and install Docker

```bash
ssh root@<hetzner-ip>

# Install Docker
curl -fsSL https://get.docker.com | sh
systemctl enable docker
systemctl start docker
```

### 5.3 Download the India OSM extract and run OSRM

OSRM needs a pre-processed map extract. We use Geofabrik's India extract.

```bash
mkdir -p /opt/osrm/data
cd /opt/osrm/data

# Download India map (~700 MB)
wget https://download.geofabrik.de/asia/india-latest.osm.pbf

# Extract + pre-process for car routing (takes ~10–15 min)
docker run --rm -t -v $(pwd):/data osrm/osrm-backend:v5.27.1 \
  osrm-extract -p /opt/car.lua /data/india-latest.osm.pbf

docker run --rm -t -v $(pwd):/data osrm/osrm-backend:v5.27.1 \
  osrm-partition /data/india-latest.osrm

docker run --rm -t -v $(pwd):/data osrm/osrm-backend:v5.27.1 \
  osrm-customize /data/india-latest.osrm
```

### 5.4 Run OSRM as a persistent service

```bash
# Run OSRM HTTP server (restart always so it survives reboots)
docker run -d --restart=always --name osrm \
  -p 5000:5000 \
  -v /opt/osrm/data:/data \
  osrm/osrm-backend:v5.27.1 \
  osrm-routed --algorithm mld /data/india-latest.osrm --max-table-size 10000

# Test it
curl "http://localhost:5000/health"
# Should return: {"status":"ok"}
```

`--max-table-size 10000` lets you request up to 10,000×10,000 origin-destination
matrices (needed for the M3 nightly replan).

### 5.5 Firewall — only allow Render to call OSRM

OSRM has no auth. Don't expose it to the public internet.

```bash
# Allow SSH
ufw allow 22/tcp

# Allow OSRM only from Render's static egress IPs
# Get Render's IP ranges: https://docs.render.com/static-outbound-ip-addresses
# Example (check the doc for current list):
ufw allow from 34.105.110.0/23 to any port 5000
ufw allow from 104.196.0.0/14 to any port 5000

# Deny everything else on port 5000
ufw deny 5000/tcp

ufw enable
```

> Alternatively: put OSRM behind a simple HTTP Basic Auth nginx proxy so you
> can use `http://user:pass@<hetzner-ip>:5000` in your env var instead of
> relying on IP allowlisting.

### 5.6 Update Render env var

Go back to Render → `1dd-api` → **Environment** → set:
```
OSRM_BASE_URL = http://<hetzner-ip>:5000
```

---

## Part 6 — GitHub Secrets (~10 min)

These are used by the CI workflow. Go to GitHub → repo → **Settings** →
**Secrets and variables** → **Actions** → **New repository secret**.

| Secret name | Value |
|---|---|
| `RENDER_STAGING_DEPLOY_HOOK` | Render → `1dd-api` → **Settings** → **Deploy Hook** URL |

That's the only secret CI needs right now. Render handles its own deployment
via the GitHub integration; you only need the deploy hook if you want to
manually trigger deploys from a GitHub Actions step (e.g. post smoke-test
trigger to prod).

---

## Part 7 — Vercel (UIs, set up now even as placeholders)

Even though the UIs don't have real code yet, create the Vercel projects now
so the structure is in place. When a developer creates `ui/da/` in the repo,
they just point their existing Vercel project at it.

### 7.1 Create a `ui/` directory structure in the repo

```
ui/
  da/          ← DA app (React, Vite)
  customer/    ← Customer app
  station/     ← Station manager app
  hub/         ← Hub ops app
```

Bootstrap each with Vite (run these in the repo root):
```bash
npm create vite@latest ui/da       -- --template react-ts
npm create vite@latest ui/customer -- --template react-ts
npm create vite@latest ui/station  -- --template react-ts
npm create vite@latest ui/hub      -- --template react-ts
```

Commit these placeholder React apps.

### 7.2 Create Vercel projects

For **each** of the 4 UIs:

1. Vercel dashboard → **Add New Project** → **Import Git Repository** →
   select `one_day_delivery`.
2. **Root Directory**: set to `ui/da` (or `ui/customer`, etc.).
3. **Framework Preset**: Vite.
4. **Build Command**: `npm run build`
5. **Output Directory**: `dist`
6. Click **Deploy**.

Vercel creates:
- `1dd-da.vercel.app` (prod URL, deploys from `main`)
- Auto-generated preview URLs for every branch push
  (e.g. `1dd-da-git-feature-grid.vercel.app`)

### 7.3 Set Vercel env vars (VITE_ prefix for client-side)

In each Vercel project → **Settings** → **Environment Variables**:

| Variable | Staging | Production |
|---|---|---|
| `VITE_API_BASE_URL` | `https://1dd-api.onrender.com` | `https://api.yourprod.com` |

For preview envs, Vercel automatically uses the staging value unless you
override it per-environment.

---

## Part 8 — Full developer workflow (day-to-day)

This is what every developer does when working on a feature.

### Starting a new feature

```bash
git checkout main && git pull
git checkout -b feature/my-feature

# Start local infrastructure
docker compose up -d

# Run the app locally
# (set your .env first — copy .env.example to .env)
export $(cat .env | xargs)
cd app && mvn spring-boot:run
```

App runs at `http://localhost:8080`.

### Sharing your branch for review

```bash
git push origin feature/my-feature
# Open a PR on GitHub

# Within ~5 minutes:
# - GitHub Actions CI runs (build + test)
# - Render spins up a preview env at:
#   https://1dd-api-pr-<number>.onrender.com
# - If you have a UI change, Vercel also generates:
#   https://1dd-da-git-feature-my-feature.vercel.app
```

Share both URLs with your reviewer. They can hit the API or open the UI
without needing to clone your branch.

### Merging

When the PR is approved and CI is green:
1. Merge to `main`.
2. Render auto-deploys `1dd-api` staging with the new code.
3. Preview env is auto-deleted.
4. Run a quick smoke test on staging.
5. Manually promote to production when ready.

---

## Part 9 — Database migrations

Never use `ddl-auto: create` or `ddl-auto: update` in cloud environments.
Use **Flyway** for all schema changes.

### Add Flyway to `app/pom.xml`

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

### Migration files live at

```
app/src/main/resources/db/migration/
  V1__init_schema.sql
  V2__add_grid_tiles.sql
  ...
```

Flyway runs automatically on startup. Preview environments get a fresh DB each
time, with all migrations applied from scratch — no manual setup.

---

## Part 10 — Environment variables reference (complete list)

| Variable | Local | Render (staging + prod) | Notes |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `render` | Picks up the right `application-{profile}.yml` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/oneday_dev` | Set by Render from DB | |
| `SPRING_DATASOURCE_USERNAME` | `oneday` | Set by Render from DB | |
| `SPRING_DATASOURCE_PASSWORD` | `oneday` | Set by Render from DB | |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `*.upstash.io:9092` | |
| `KAFKA_SASL_JAAS_USERNAME` | _(not needed locally)_ | Upstash username | |
| `KAFKA_SASL_JAAS_PASSWORD` | _(not needed locally)_ | Upstash password | |
| `OSRM_BASE_URL` | `http://localhost:5000` | `http://<hetzner-ip>:5000` | Self-hosted, no per-call cost |
| `JWT_SECRET` | any string ≥32 chars | Render auto-generates | |

---

## Checklist — do this in order

- [ ] **Accounts**: Create Render, Upstash, Hetzner, Vercel accounts
- [ ] **Upstash**: Create Kafka cluster, copy bootstrap/username/password
- [ ] **Repo files**: Add `Dockerfile`, `.dockerignore`, `docker-compose.yml`,
      `.env.example`, `render.yaml`, `.github/workflows/ci.yml`
- [ ] **Spring profiles**: Add `application-render.yml` and `application-local.yml`
- [ ] **Render**: Import repo via Blueprint, fill in Kafka secrets in dashboard
- [ ] **Hetzner**: Provision server, install Docker, process India OSM, run OSRM
- [ ] **Render**: Set `OSRM_BASE_URL` env var
- [ ] **Firewall**: Lock Hetzner port 5000 to Render IPs only
- [ ] **UI dirs**: Bootstrap 4 Vite apps in `ui/`, commit
- [ ] **Vercel**: Create 4 projects, set `VITE_API_BASE_URL` per env
- [ ] **Flyway**: Add dependency, create `V1__init_schema.sql`
- [ ] **Smoke test**: Push a branch, open a PR, verify preview env appears
- [ ] **Verify**: Hit `https://1dd-api.onrender.com/actuator/health` → `UP`
