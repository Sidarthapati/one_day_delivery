import { MapContainer, TileLayer, Polygon, Tooltip, useMap } from 'react-leaflet'
import { cellToBoundary } from 'h3-js'
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

function RecenterMap({ center }) {
  const map = useMap()
  useEffect(() => {
    map.setView(center, map.getZoom())
  }, [center, map])
  return null
}

export default function HexMap({ tiles, assignmentMap, mode, onHexClick, selectedHexId, center }) {
  const maxDemand = Math.max(...tiles.map(t => t.demandScoreMinutes || 0), 1)

  return (
    <MapContainer
      center={center}
      zoom={11}
      style={{ height: '100%', width: '100%' }}
      zoomControl={true}
    >
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
      />
      <RecenterMap center={center} />
      {tiles.map(hex => {
        const vertices = cellToBoundary(hex.h3Index)
        const daId = assignmentMap[hex.id]
        const isSelected = hex.id === selectedHexId

        let fillColor
        if (!hex.active) {
          fillColor = '#9ca3af'
        } else if (mode === 'demand') {
          fillColor = demandColor(hex.demandScoreMinutes, maxDemand)
        } else {
          fillColor = hashDaColor(daId)
        }

        return (
          <Polygon
            key={hex.id}
            positions={vertices}
            pathOptions={{
              color: isSelected ? '#1d4ed8' : '#475569',
              weight: isSelected ? 2 : 0.5,
              fillColor,
              fillOpacity: hex.active ? 0.65 : 0.3,
            }}
            eventHandlers={{ click: () => onHexClick(hex.id) }}
          >
            <Tooltip sticky>
              {hex.active
                ? `${hex.h3Index.slice(0, 10)}… · ${Math.round(hex.demandScoreMinutes)} min`
                : `${hex.h3Index.slice(0, 10)}… · inactive`}
            </Tooltip>
          </Polygon>
        )
      })}
    </MapContainer>
  )
}
