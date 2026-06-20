import { MapContainer, TileLayer, Polyline, Marker, Tooltip, useMap } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { hashDaColor } from '../utils/daColors.js'
import { useEffect } from 'react'

// Van marker — colour by lateness (green on time, amber minor, red past threshold, blue = moving/no
// arrival yet). Pulses so a live van reads as "moving" vs the static numbered route stops.
function vanIcon(color, label) {
  return L.divIcon({
    className: '',
    html: `<div style="position:relative;display:flex;align-items:center;justify-content:center">
      <div style="position:absolute;width:30px;height:30px;border-radius:50%;background:${color};opacity:.30;
        animation:vanpulse 1.4s ease-out infinite"></div>
      <div style="background:${color};color:#fff;min-width:30px;height:18px;padding:0 5px;border-radius:9px;
        display:flex;align-items:center;justify-content:center;font:800 10px/1 sans-serif;
        border:2px solid #fff;box-shadow:0 0 5px rgba(0,0,0,.55);white-space:nowrap">🚐 ${label}</div>
    </div>
    <style>@keyframes vanpulse{0%{transform:scale(.6);opacity:.5}100%{transform:scale(1.8);opacity:0}}</style>`,
    iconSize: [40, 24], iconAnchor: [20, 12],
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

function lateColor(minutesLate) {
  if (minutesLate == null) return '#2563eb'        // moving, no arrival recorded yet
  if (minutesLate >= 10) return '#dc2626'          // past the late threshold
  if (minutesLate >= 6) return '#d97706'           // minor slip
  return '#16a34a'                                  // on time
}

function FitOnce({ routes, nodes }) {
  const map = useMap()
  useEffect(() => {
    const pts = []
    routes.forEach(r => r.geometry.forEach(p => pts.push(p)))
    nodes.forEach(n => pts.push([n.lat, n.lon]))
    if (pts.length >= 2) map.fitBounds(pts, { padding: [50, 50] })
  }, [routes.length, nodes.length, map])
  return null
}

export default function ExecutionMap({ center, routes = [], nodes = [], vans = [], vanColor = hashDaColor }) {
  return (
    <MapContainer center={center} zoom={11} style={{ height: '100%', width: '100%' }}>
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
      />
      <FitOnce routes={routes} nodes={nodes} />

      {/* Static route polylines (the approved plan), faded under the live vans. */}
      {routes.map(r => (
        <Polyline key={r.vanId} positions={r.geometry}
          pathOptions={{ color: vanColor(r.vanId), weight: 4, opacity: 0.35, lineJoin: 'round' }} />
      ))}

      {/* One numbered dot per meeting vertex (the plan's stops). */}
      {routes.map(r => (r.markers || []).map(m => (
        <Marker key={`${r.vanId}-${m.stopId}`} position={[m.lat, m.lon]}
          icon={L.divIcon({
            className: '',
            html: `<div style="background:${vanColor(r.vanId)};color:#fff;width:18px;height:18px;border-radius:50%;
              display:flex;align-items:center;justify-content:center;font:700 10px/1 sans-serif;
              opacity:.85;border:2px solid #fff;box-shadow:0 0 3px rgba(0,0,0,.4)">${m.seq}</div>`,
            iconSize: [18, 18], iconAnchor: [9, 9],
          })}>
          <Tooltip>Stop {m.seq}</Tooltip>
        </Marker>
      )))}

      {/* Live vans. */}
      {vans.map(v => (
        v.lastLat == null ? null : (
          <Marker key={v.vanId} position={[v.lastLat, v.lastLon]}
            icon={vanIcon(lateColor(v.minutesLate), v.vanId.slice(0, 4))} zIndexOffset={1000}>
            <Tooltip>
              <div style={{ fontSize: 12 }}>
                <strong>Van {v.vanId.slice(0, 8)}</strong><br />
                {v.currentStopSeq != null ? `at stop ${v.currentStopSeq}` : 'en route'}<br />
                {v.minutesLate == null ? 'no arrival yet'
                  : v.minutesLate >= 10 ? `⚠ running late +${v.minutesLate}m`
                  : v.minutesLate > 0 ? `+${v.minutesLate}m` : 'on time'}
              </div>
            </Tooltip>
          </Marker>
        )
      ))}

      {nodes.map(n => (
        <Marker key={n.kind} position={[n.lat, n.lon]} icon={nodeIcon(n.kind)} zIndexOffset={1200}>
          <Tooltip>{n.name}</Tooltip>
        </Marker>
      ))}
    </MapContainer>
  )
}
