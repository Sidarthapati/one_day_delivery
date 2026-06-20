import { osrmRoute } from '../api/routingApi.js'

// Build per-van route geometry from M6 plan stops. Every loop visits the SAME vertices in the SAME
// order (only times differ), so we road-snap ONE representative loop (hub → vertices → hub) via OSRM
// and report per-loop vs whole-day distance. Returns one entry per van:
//   { vanId, geometry, markers[{seq,lat,lon,visits[]}], stopsByLoop[][], loopIndices[],
//     loopCount, vertexCount, loopMinutes, perLoopDistanceKm, totalDistanceKm }
export async function buildRoutes(stops, nodes) {
  const hub = nodes.find(n => n.kind === 'HUB')
  const hubLL = hub ? [hub.lat, hub.lon] : null
  const byVan = new Map()
  for (const s of stops) {
    if (!byVan.has(s.vanId)) byVan.set(s.vanId, [])
    byVan.get(s.vanId).push(s)
  }

  const result = []
  for (const [vanId, vanStops] of byVan) {
    vanStops.sort((a, b) => a.loopIndex - b.loopIndex || a.stopSeq - b.stopSeq)
    const loopIndices = [...new Set(vanStops.map(s => s.loopIndex))].sort((a, b) => a - b)
    const loopCount = loopIndices.length

    const repStops = vanStops.filter(s => s.loopIndex === loopIndices[0]).sort((a, b) => a.stopSeq - b.stopSeq)
    const vertexCount = repStops.length

    const visitsBySeq = {}
    for (const s of vanStops) {
      (visitsBySeq[s.stopSeq] ||= []).push({
        loop: s.loopIndex, arr: s.plannedArrival, dep: s.plannedDeparture,
        deliver: s.deliverQty, collect: s.collectQty, load: s.loadAfter,
      })
    }
    const markers = repStops.map(s => ({
      stopId: s.stopId, seq: s.stopSeq, lat: s.lat, lon: s.lon, visits: visitsBySeq[s.stopSeq] || [],
    }))

    const stopsByLoop = loopIndices.map(li =>
      vanStops.filter(s => s.loopIndex === li).sort((a, b) => a.stopSeq - b.stopSeq))

    const toMin = t => { const [h, m, s] = t.split(':').map(Number); return h * 60 + m + (s || 0) / 60 }
    let loopMinutes = null
    if (repStops.length) {
      if (loopCount >= 2 && stopsByLoop[0][0] && stopsByLoop[1][0]) {
        loopMinutes = Math.round(toMin(stopsByLoop[1][0].plannedArrival) - toMin(stopsByLoop[0][0].plannedArrival))
      } else {
        loopMinutes = Math.round(toMin(repStops[repStops.length - 1].plannedDeparture) - toMin(repStops[0].plannedArrival))
      }
    }

    const waypoints = []
    if (hubLL) waypoints.push(hubLL)
    repStops.forEach(s => waypoints.push([s.lat, s.lon]))
    if (hubLL) waypoints.push(hubLL)
    const r = await osrmRoute(waypoints)
    const perLoopDistanceKm = r?.distanceKm ?? null

    result.push({
      vanId,
      geometry: r?.geometry || waypoints,
      markers,
      stopsByLoop,
      loopIndices,
      loopCount,
      vertexCount,
      loopMinutes,
      perLoopDistanceKm,
      totalDistanceKm: perLoopDistanceKm != null ? perLoopDistanceKm * loopCount : null,
    })
  }
  return result
}
