import { MapContainer, TileLayer, Polygon, Polyline, Marker, Tooltip, useMap } from 'react-leaflet'
import { cellToBoundary } from 'h3-js'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { hashDaColor } from '../utils/daColors.js'
import { useEffect } from 'react'

function demandColor(minutes, maxMinutes) {
  if (!minutes || maxMinutes === 0) return '#e5e7eb'
  const ratio = Math.min(minutes / maxMinutes, 1)
  const r = Math.round(34 + ratio * (239 - 34))
  const g = Math.round(197 - ratio * (197 - 68))
  const b = Math.round(94 - ratio * (94 - 68))
  return `rgb(${r},${g},${b})`
}

// ── Leaflet divIcons (no external image assets) ──────────────────────────────
function stopIcon(color, label) {
  return L.divIcon({
    className: '',
    html: `<div style="background:${color};color:#fff;width:24px;height:24px;border-radius:50%;
      display:flex;align-items:center;justify-content:center;font:700 11px/1 sans-serif;
      border:2px solid #fff;box-shadow:0 0 4px rgba(0,0,0,.45)">${label}</div>`,
    iconSize: [24, 24], iconAnchor: [12, 12],
  })
}
function nodeIcon(kind) {
  const hub = kind === 'HUB'
  const bg = hub ? '#111827' : '#2563eb'
  const txt = hub ? '🏢 HUB' : '✈ AIRPORT'
  return L.divIcon({
    className: '',
    html: `<div style="background:${bg};color:#fff;padding:4px 9px;border-radius:5px;
      font:800 12px/1 sans-serif;border:2px solid #fff;box-shadow:0 1px 5px rgba(0,0,0,.5);
      white-space:nowrap;letter-spacing:.3px">${txt}</div>`,
    iconSize: [hub ? 56 : 78, 26], iconAnchor: [hub ? 28 : 39, 13],
  })
}

function RecenterMap({ center, active }) {
  const map = useMap()
  useEffect(() => { if (active) map.setView(center, 11) }, [center, active, map])
  return null
}

// In routes mode, fit the map to the visible van loops + nodes so the routes fill the screen.
function FitToRoutes({ routes, selectedVan, nodes, active }) {
  const map = useMap()
  useEffect(() => {
    if (!active) return
    const pts = []
    routes.forEach(r => {
      if (selectedVan && r.vanId !== selectedVan) return
      r.geometry.forEach(p => pts.push(p))
    })
    nodes.forEach(n => pts.push([n.lat, n.lon]))
    if (pts.length >= 2) map.fitBounds(pts, { padding: [50, 50] })
  }, [routes, selectedVan, active, nodes, map])
  return null
}

export default function HexMap({
  tiles, assignmentMap, mode, onHexClick, selectedHexId, center,
  routes = [], nodes = [], selectedVan = null, vanColor = hashDaColor,
}) {
  const maxDemand = Math.max(...tiles.map(t => t.demandScoreMinutes || 0), 1)
  const routesMode = mode === 'routes'

  return (
    <MapContainer center={center} zoom={11} style={{ height: '100%', width: '100%' }} zoomControl={true}>
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
      />
      <RecenterMap center={center} active={!routesMode} />
      <FitToRoutes routes={routes} selectedVan={selectedVan} nodes={nodes} active={routesMode} />

      {/* Hex layer. In routes mode the territory colouring is drawn faded UNDER the loops so you can
          check the meeting-vertex set sits sensibly within each DA territory. */}
      {tiles.map(hex => {
        const daId = assignmentMap[hex.id]
        const isSelected = hex.id === selectedHexId
        let fillColor
        if (!hex.active) fillColor = '#9ca3af'
        else if (routesMode) fillColor = hashDaColor(daId)
        else if (mode === 'demand') fillColor = demandColor(hex.demandScoreMinutes, maxDemand)
        else fillColor = hashDaColor(daId)
        return (
          <Polygon
            key={hex.id}
            positions={cellToBoundary(hex.h3Index)}
            interactive={!routesMode}
            pathOptions={{
              color: isSelected ? '#1d4ed8' : '#475569',
              weight: isSelected ? 2 : routesMode ? 0.2 : 0.5,
              fillColor,
              fillOpacity: routesMode ? (daId ? 0.22 : 0.05) : hex.active ? 0.65 : 0.3,
            }}
            eventHandlers={routesMode ? undefined : { click: () => onHexClick(hex.id) }}
          >
            {!routesMode && (
              <Tooltip sticky>
                {hex.active
                  ? `${hex.h3Index.slice(0, 10)}… · ${Math.round(hex.demandScoreMinutes)} min`
                  : `${hex.h3Index.slice(0, 10)}… · inactive`}
              </Tooltip>
            )}
          </Polygon>
        )
      })}

      {/* Route polylines (drawn under markers) */}
      {routesMode && routes.map(r => {
        if (selectedVan && r.vanId !== selectedVan) return null
        const color = vanColor(r.vanId)
        const dim = selectedVan && r.vanId !== selectedVan
        return (
          <Polyline key={r.vanId} positions={r.geometry}
            pathOptions={{ color, weight: 5, opacity: dim ? 0.25 : 0.9, lineJoin: 'round' }} />
        )
      })}

      {/* One numbered marker per meeting vertex (visit order within a loop). The same vertex is
          revisited every loop, so the tooltip lists all of the day's visit times at that point. */}
      {routesMode && routes.map(r => (
        (selectedVan && r.vanId !== selectedVan) ? null : (r.markers || []).map(m => (
          <Marker key={m.stopId} position={[m.lat, m.lon]} icon={stopIcon(vanColor(r.vanId), m.seq)}>
            <Tooltip>
              <div style={{ fontSize: 12 }}>
                <strong>Stop {m.seq}</strong> · {m.visits.length} visit{m.visits.length > 1 ? 's' : ''}/day<br />
                {m.visits.map(v => (
                  <span key={v.loop}>loop {v.loop + 1}: {v.arr}–{v.dep} · ▼{v.deliver} ▲{v.collect}<br /></span>
                ))}
              </div>
            </Tooltip>
          </Marker>
        ))
      ))}

      {/* Hub + airport — always visible, clearly labelled */}
      {nodes.map(n => (
        <Marker key={n.kind} position={[n.lat, n.lon]} icon={nodeIcon(n.kind)}
          zIndexOffset={1000}>
          <Tooltip>{n.name}</Tooltip>
        </Marker>
      ))}
    </MapContainer>
  )
}
