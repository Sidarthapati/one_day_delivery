const BASE = 'http://localhost:8080'

async function req(path, opts = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  })
  if (!res.ok) throw new Error(`${opts.method || 'GET'} ${path} → ${res.status}`)
  if (res.status === 204) return null
  return res.json()
}

export const fetchTiles = (cityCode, date) =>
  req(`/api/grid/${cityCode}/tiles?date=${date}`)

export const fetchAssignments = (cityCode, date) =>
  req(`/api/grid/${cityCode}/assignments?date=${date}`)

export const fetchProposals = (cityCode, date) =>
  req(`/api/proposals?cityCode=${cityCode}&date=${date}`)

export const replan = (cityCode, daCount, date) => {
  const daIds = Array.from({ length: daCount }, () => crypto.randomUUID())
  return req(`/api/grid/${cityCode}/replan`, {
    method: 'POST',
    body: JSON.stringify({ daIds, date }),
  })
}

export const approveProposal = (proposalId) =>
  req(`/api/proposals/${proposalId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ reviewerId: '00000000-0000-0000-0000-000000000001' }),
  })

export const setTileActive = (cityCode, hexId, active) =>
  req(`/api/grid/${cityCode}/tiles/${hexId}/active?active=${active}`, {
    method: 'PATCH',
  })

export const updateTileDemand = (hexId, demandScoreMinutes) =>
  req(`/api/demo/hexes/${hexId}/demand`, {
    method: 'PUT',
    body: JSON.stringify({ demandScoreMinutes }),
  })

export const fetchTileDetail = (hexId, date) =>
  req(`/api/demo/hexes/${hexId}/detail?date=${date}`)
