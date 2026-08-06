import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev proxy so the browser talks same-origin (no CORS): the backend (/api, /routing) and the
// Hetzner OSRM (/osrm → road-snapped route geometry) are both reached through Vite. Override the
// targets with VITE_BACKEND / VITE_OSRM env vars to point at a deployed backend.
const BACKEND = process.env.VITE_BACKEND || 'http://localhost:8080'
// Hetzner OSRM (46.225.155.64:5000) is down — default to the public OSRM so route lines draw.
const OSRM = process.env.VITE_OSRM || 'https://router.project-osrm.org'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/routing': { target: BACKEND, changeOrigin: true },
      '/internal': { target: BACKEND, changeOrigin: true },
      '/osrm': {
        target: OSRM,
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/osrm/, ''),
      },
    },
  },
})
