// M6 routing endpoints + OSRM road geometry. Same-origin via Vite proxy (/routing → backend,
// /osrm → Hetzner OSRM). Responses deep-converted to camelCase (shared with gridApi).
import { deepCamel } from './gridApi.js'

async function req(path, opts = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts })
  if (!res.ok) {
    let detail = ''
    try { detail = (await res.json()).detail || '' } catch { /* ignore */ }
    throw new Error(`${opts.method || 'GET'} ${path} → ${res.status}${detail ? ` (${detail})` : ''}`)
  }
  if (res.status === 204) return null
  return deepCamel(await res.json())
}

// ── Fleet config ─────────────────────────────────────────────────────────────
export const getFleet = (cityId) => req(`/routing/fleet/${cityId}`)

export const putFleet = (cityId, patch) =>
  req(`/routing/fleet/${cityId}`, { method: 'PUT', body: JSON.stringify(patch) })

export const getNodes = (cityId) => req(`/routing/nodes/${cityId}`)

// ── Plan lifecycle ───────────────────────────────────────────────────────────
export const m6Replan = (cityId, date) =>
  req(`/routing/plans/${cityId}/replan?date=${date}`, { method: 'POST' })

export const m6Approve = (planId) =>
  req(`/routing/plans/${planId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ actor_id: '00000000-0000-0000-0000-000000000001' }),
  })

export const getPlan = (cityId, date) => req(`/routing/plans/${cityId}?date=${date}`)

export const getAllStops = (cityId, date) => req(`/routing/plans/${cityId}/stops?date=${date}`)

export const getShuttle = (cityId, date) => req(`/routing/shuttle/${cityId}?date=${date}`)

// ── OSRM road-snapped geometry ───────────────────────────────────────────────
// latLngs: array of [lat, lon]. Returns { geometry:[[lat,lon]…], distanceKm } along the road, or
// null on failure (caller falls back to straight segments). OSRM wants lon,lat order.
export async function osrmRoute(latLngs) {
  if (!latLngs || latLngs.length < 2) return null
  const coords = latLngs.map(([lat, lon]) => `${lon},${lat}`).join(';')
  try {
    const res = await fetch(`/osrm/route/v1/driving/${coords}?overview=full&geometries=geojson`)
    if (!res.ok) return null
    const data = await res.json()
    if (data.code !== 'Ok' || !data.routes?.length) return null
    // GeoJSON coords are [lon, lat] → flip to [lat, lon] for Leaflet.
    return {
      geometry: data.routes[0].geometry.coordinates.map(([lon, lat]) => [lat, lon]),
      distanceKm: data.routes[0].distance / 1000,
    }
  } catch {
    return null
  }
}
