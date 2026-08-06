// Same-origin (Vite proxies /api → backend). The backend serializes snake_case (global Jackson
// strategy), so responses are deep-converted to camelCase here and the rest of the UI stays
// camelCase. Request bodies are written snake_case explicitly (small + few).

const BASE = ''

function toCamel(s) {
  return s.replace(/_([a-z0-9])/g, (_, c) => c.toUpperCase())
}

// Recursively rewrite object keys snake_case → camelCase (arrays + nested objects).
export function deepCamel(v) {
  if (Array.isArray(v)) return v.map(deepCamel)
  if (v && typeof v === 'object') {
    return Object.fromEntries(Object.entries(v).map(([k, val]) => [toCamel(k), deepCamel(val)]))
  }
  return v
}

async function req(path, opts = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  })
  if (!res.ok) {
    let detail = ''
    try { detail = (await res.json()).detail || '' } catch { /* ignore */ }
    throw new Error(`${opts.method || 'GET'} ${path} → ${res.status}${detail ? ` (${detail})` : ''}`)
  }
  if (res.status === 204) return null
  return deepCamel(await res.json())
}

// ── Grid reads ───────────────────────────────────────────────────────────────
export const fetchTiles = (cityCode, date) =>
  req(`/api/grid/${cityCode}/tiles?date=${date}`)

export const fetchAssignments = (cityCode, date) =>
  req(`/api/grid/${cityCode}/assignments?date=${date}`)

export const fetchProposals = (cityCode, date) =>
  req(`/api/proposals?cityCode=${cityCode}&date=${date}`)

export const fetchVertices = (cityCode) =>
  req(`/api/grid/${cityCode}/vertices`)

// ── Demand seeding (explicit, separate step) ─────────────────────────────────
// Seeding is its own action now — territory/route generation REUSES whatever snapshot exists
// rather than re-rolling demand each run. `seed` makes the surface reproducible; omit it to let
// the backend pick (and return) a random one.
export const seedDemand = (cityCode, date, { minMinutes = 4, maxMinutes = 10, seed } = {}) => {
  const seedParam = seed != null && seed !== '' ? `&seed=${seed}` : ''
  return req(
    `/api/demo/seed?cityCode=${cityCode}&minMinutes=${minMinutes}&maxMinutes=${maxMinutes}${seedParam}&date=${date}`,
    { method: 'POST' })
}

// How many demand rows already exist for a city/date — used to hard-fail territory gen if unseeded.
export const demandCount = (cityCode, date) =>
  req(`/api/demo/demand-count?cityCode=${cityCode}&date=${date}`)

// ── M3 lifecycle (territories) ───────────────────────────────────────────────

export const replan = (cityCode, daCount, date) => {
  const daIds = Array.from({ length: daCount }, () => crypto.randomUUID())
  return req(`/api/grid/${cityCode}/replan`, {
    method: 'POST',
    body: JSON.stringify({ da_ids: daIds, date }),
  })
}

export const approveProposal = (proposalId) =>
  req(`/api/proposals/${proposalId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewer_id: '00000000-0000-0000-0000-000000000001' }),
  })

// Demo go-live: flip the date's nightly-APPROVED assignments to ACTIVE (what M6 territory reads use).
export const activateAssignments = (cityCode, date) =>
  req(`/api/demo/activate?cityCode=${cityCode}&date=${date}`, { method: 'POST' })

// ── Hex demand editing ───────────────────────────────────────────────────────
export const setTileActive = (cityCode, hexId, active) =>
  req(`/api/grid/${cityCode}/tiles/${hexId}/active?active=${active}`, { method: 'PATCH' })

export const updateTileDemand = (hexId, demandScoreMinutes) =>
  req(`/api/demo/hexes/${hexId}/demand`, {
    method: 'PUT',
    body: JSON.stringify({ demand_score_minutes: demandScoreMinutes }),
  })

export const fetchTileDetail = (hexId, date) =>
  req(`/api/demo/hexes/${hexId}/detail?date=${date}`)
