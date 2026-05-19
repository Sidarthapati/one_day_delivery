# OSRM Setup

Single OSRM server on Hetzner — used by both local dev and production (Render).
No Docker needed on your Mac.

**Current state (as of May 2026):**
- Server: `46.225.155.64` (Hetzner CPX41, EU Central)
- Status: running, verified working
- `grid/src/main/resources/application.yml` already points at this IP
- UFW firewall active — port 5000 open to dev IP + Render ranges only

---

## How OSRM works in this system

OSRM is a self-hosted routing engine running on OpenStreetMap data. We use
exactly two endpoints — `/table` for the adjacency matrix and `/route` for
per-tile traversal caps. No turn-by-turn directions, no live routing.

### The two calls we make

#### Call 1 — Adjacency matrix (once per city per month)

We take every active tile in the city, compute each tile's centroid, and send
all of them in a single `/table` request.

```
centroid(tile) = (
    origin_lat + (row_idx + 0.5) × tile_delta_lat,
    origin_lon + (col_idx + 0.5) × tile_delta_lon
)
```

> Note: OSRM uses **lon, lat** order — not lat, lon.

OSRM returns a `durations[][]` matrix — seconds of road travel time between
every pair. We apply the adjacency threshold (`grid.osrm.adjacency-threshold-seconds`,
default 600s). Pairs under the threshold are road neighbours; pairs over it are
not — even if the tiles are geometrically adjacent (a highway or railway between
them can make road travel > 10 min).

Neighbours are stored in `tile_travel_time` and used by the CP-SAT solver for
contiguity validation.

#### Call 2 — Traversal cap per tile (once at city initialisation)

For each tile, we compute the OSRM road time from its SW corner to its NE
corner — the longest plausible intra-tile trip. Stored as
`tile.traversal_cap_sec`. The demand scoring service uses it to winsorise
inter-stop travel measurements.

### After setup — OSRM is never called at replan time

The nightly replan reads from `tile_travel_time` in the DB. OSRM is completely
out of the picture during planning — only called at city init and monthly refresh.

---

## Why osmium city crops, not the full India extract

The India `.osm.pbf` is ~1.6 GB on disk. OSRM's extract step builds an
edge-expanded graph in memory that peaked at **15.2 GB RSS** — it OOM-killed
even on a 16 GB server.

The fix: use `osmium` (a streaming tool, ~1 GB RAM) to crop just the 5 city
bounding boxes out of the India PBF first. The merged 5-city file is 151 MB.
OSRM's extract on 151 MB peaks at only **2 GB RAM** — no OOM risk on any server.

**City bounding boxes loaded (lon_min, lat_min, lon_max, lat_max):**

| City | Bounding box | Coverage |
|------|-------------|----------|
| Bangalore | 77.30, 12.70, 77.85, 13.20 | ~55 km × 55 km |
| Chennai | 79.95, 12.85, 80.45, 13.30 | ~55 km × 50 km |
| Mumbai | 72.70, 18.85, 73.10, 19.35 | ~44 km × 55 km |
| Hyderabad | 78.25, 17.15, 78.75, 17.65 | ~55 km × 55 km |
| Delhi | 76.85, 28.40, 77.55, 29.00 | ~77 km × 66 km |

Each box covers ~27×27 tiles at our 2 km tile size — more than enough for any
city's operational area. If you ever add a city or expand a grid near the edge
of a box, just widen the bbox and re-run the osmium + OSRM steps (~15 min).

---

## Part A — Hetzner server setup (one-time, already done)

Documented here for reproducibility if the server needs to be rebuilt.

### A.1 — Provision the server

- Hetzner Cloud → project `1dd-infra` → **Add Server**
- Location: **EU Central** (Falkenstein or Nuremberg — ~$16/month cheaper than Singapore for zero practical latency difference, since OSRM is called monthly not in real-time)
- Image: **Ubuntu 24.04**
- Type: **CPX41** (8 vCPU, 16 GB RAM) — needed because osmium extracts a 151 MB merged PBF, and OSRM then peaks at ~2 GB. CX31/CPX32 (8 GB) was tried and OOM-killed during the full India extract. CPX41 at ~$38/month is the right size.
- SSH key: add your public key (`cat ~/.ssh/id_ed25519.pub`) in Security → SSH Keys **before** creating the server, then select it on the creation screen.
- Name: `1dd-osrm`

### A.2 — SSH in and install Docker

```bash
ssh root@<hetzner-ip>

curl -fsSL https://get.docker.com | sh
systemctl enable docker
systemctl start docker
```

### A.3 — Add swap (safety net for osmium merge)

```bash
fallocate -l 4G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

### A.4 — Install osmium and download India PBF

```bash
apt-get install -y osmium-tool

mkdir -p /opt/osrm/data
cd /opt/osrm/data
wget https://download.geofabrik.de/asia/india-latest.osm.pbf
```

### A.5 — Crop 5 cities and merge

```bash
cat > /opt/osrm/data/cities.geojson << 'EOF'
{
  "extracts": [
    { "output": "bangalore.osm.pbf",  "bbox": [77.30, 12.70, 77.85, 13.20] },
    { "output": "chennai.osm.pbf",    "bbox": [79.95, 12.85, 80.45, 13.30] },
    { "output": "mumbai.osm.pbf",     "bbox": [72.70, 18.85, 73.10, 19.35] },
    { "output": "hyderabad.osm.pbf",  "bbox": [78.25, 17.15, 78.75, 17.65] },
    { "output": "delhi.osm.pbf",      "bbox": [76.85, 28.40, 77.55, 29.00] }
  ]
}
EOF

# Extract all 5 cities in one pass (~2-3 min, ~1 GB RAM)
osmium extract --config cities.geojson --overwrite india-latest.osm.pbf

# Merge into one 151 MB file
osmium merge bangalore.osm.pbf chennai.osm.pbf mumbai.osm.pbf \
  hyderabad.osm.pbf delhi.osm.pbf -o cities-merged.osm.pbf --overwrite
```

### A.6 — Run the MLD pipeline on the merged file

```bash
# Extract (~30s, peaks at 2 GB RAM)
docker run --rm -v /opt/osrm/data:/data \
  osrm/osrm-backend:latest \
  osrm-extract -p /opt/car.lua /data/cities-merged.osm.pbf

# Partition (~30s)
docker run --rm -v /opt/osrm/data:/data \
  osrm/osrm-backend:latest \
  osrm-partition /data/cities-merged.osrm

# Customize (~10s)
docker run --rm -v /opt/osrm/data:/data \
  osrm/osrm-backend:latest \
  osrm-customize /data/cities-merged.osrm
```

> Use `osrm/osrm-backend:latest` (v5.26.0). Tag v5.27.1 no longer exists on Docker Hub.

### A.7 — Start OSRM as a persistent service

```bash
docker run -d \
  --name osrm \
  --restart always \
  -p 5000:5000 \
  -v /opt/osrm/data:/data \
  osrm/osrm-backend:latest \
  osrm-routed --algorithm mld \
              --max-table-size 10000 \
              /data/cities-merged.osrm
```

**Verify** (v5.26.0 has no `/health` endpoint — use the table API directly):

```bash
curl "http://localhost:5000/table/v1/driving/77.5946,12.9716;77.6045,12.9770;77.5800,12.9600"
# Expected: JSON with "code": "Ok" and a "durations" 3x3 matrix
```

### A.8 — Firewall

```bash
ufw allow 22/tcp

# Render static outbound IPs (verify at docs.render.com/static-outbound-ip-addresses)
ufw allow from 34.105.110.0/23 to any port 5000
ufw allow from 104.196.0.0/14 to any port 5000

# Your dev machine IP (find it with: curl ifconfig.me)
ufw allow from <your-home-ip> to any port 5000

ufw deny 5000/tcp
ufw --force enable
```

**To update your home IP when it changes:**
```bash
ufw allow from <new-ip> to any port 5000
ufw delete allow from <old-ip> to any port 5000
```

---

## Part B — Local dev config (already done)

`grid/src/main/resources/application.yml` is already set to:
```yaml
grid:
  osrm:
    base-url: http://46.225.155.64:5000
    adjacency-threshold-seconds: 600
```

No further action needed for local dev.

---

## Part C — Production config (Render)

When deploying to Render, set this environment variable:

```
OSRM_BASE_URL = http://46.225.155.64:5000
```

Render dashboard → `1dd-api` → **Environment** → add → Save → auto-redeploy.

> Before doing this, verify Render's current static outbound IP ranges at
> `docs.render.com/static-outbound-ip-addresses` and confirm they match the
> UFW rules added in A.8.

---

## Part D — Monthly OSM data refresh

Re-crop from a fresh India PBF and reprocess. Takes ~15 min total.

```bash
# SSH into server
ssh root@46.225.155.64

cd /opt/osrm/data

# Download fresh India PBF
wget -O india-latest.osm.pbf https://download.geofabrik.de/asia/india-latest.osm.pbf

# Re-crop cities
osmium extract --config cities.geojson --overwrite india-latest.osm.pbf
osmium merge bangalore.osm.pbf chennai.osm.pbf mumbai.osm.pbf \
  hyderabad.osm.pbf delhi.osm.pbf -o cities-merged.osm.pbf --overwrite

# Re-run MLD pipeline
docker run --rm -v /opt/osrm/data:/data osrm/osrm-backend:latest \
  osrm-extract -p /opt/car.lua /data/cities-merged.osm.pbf

docker run --rm -v /opt/osrm/data:/data osrm/osrm-backend:latest \
  osrm-partition /data/cities-merged.osrm

docker run --rm -v /opt/osrm/data:/data osrm/osrm-backend:latest \
  osrm-customize /data/cities-merged.osrm

# Restart (OSRM down for ~5 seconds; app falls back to GEOMETRIC_FALLBACK)
docker restart osrm
```

---

## Checklist

### Hetzner server (Part A) — COMPLETE
- [x] CPX41 server provisioned in `1dd-infra` project (EU Central, `46.225.155.64`)
- [x] SSH access confirmed
- [x] Docker 29.5.1 installed and running
- [x] 4 GB swap file created
- [x] osmium-tool 1.16.0 installed
- [x] India OSM extract downloaded (`india-latest.osm.pbf`, 1.6 GB)
- [x] 5 cities cropped with osmium (151 MB merged file)
- [x] `osrm-extract` completed (2 GB RAM peak)
- [x] `osrm-partition` completed
- [x] `osrm-customize` completed
- [x] `osrm` container running with `--restart always`
- [x] Table API smoke test returns durations matrix from server and from Mac
- [x] UFW active — port 5000 restricted to dev IP + Render ranges

### Local dev (Part B) — COMPLETE
- [x] `grid/src/main/resources/application.yml` updated to `http://46.225.155.64:5000`
- [x] Table API smoke test passes from Mac

### Production (Part C) — PENDING
- [ ] `OSRM_BASE_URL` set in Render dashboard (do when deploying to Render)
- [ ] Render IPs verified against current UFW rules
