# M3 H3 Demo — Implementation Plan

Branch: `f-m3-h3-refcator-demo`  
Reference: original rectangular-tile demo on `f-M3-demo`

---

## 1. What the demo needs to do

Same feature set as the rectangle demo, adapted to H3 hexagons:

| Feature | How it worked (rect tiles) | How it works (H3 hexes) |
|---|---|---|
| City grid on a map | Leaflet `Rectangle` with SW/NE corners | Leaflet `Polygon` with 6 vertices from `h3-js.cellToBoundary(h3Index)` |
| Demand heatmap | Color each tile by `demandScoreMinutes` | Same — color each hex by `demandScoreMinutes` |
| DA territory view | Color each tile by its assigned DA | Same — color each hex by its assigned DA |
| Click hex to edit demand | Sidebar panel, PUT `/api/demo/tiles/{id}/demand` | Sidebar panel, PUT `/api/demo/hexes/{id}/demand` |
| Toggle hex active/inactive | PATCH `/api/grid/{city}/tiles/{id}/active` | Same endpoint name, just hex UUID now |
| Generate plan | POST `/api/grid/{city}/replan` with N synthetic DA UUIDs | Identical — same endpoint |
| Approve proposal | POST `/api/proposals/{id}/approve` | Identical |
| Stale banner | Shown after demand edit until plan regenerated | Same |
| City selector | Hard-coded to Delhi | Dropdown: Delhi / Mumbai / Bangalore / Hyderabad / Chennai |

---

## 2. What changes vs the old demo

### 2a. The key new piece: drawing hexagons

Old demo used `react-leaflet`'s `<Rectangle bounds={[[swLat, swLon], [neLat, neLon]]} />`.

H3 hexes don't have a bounding box — they have 6 vertices. The `h3-js` browser library provides:
```js
import { cellToBoundary } from 'h3-js'
const vertices = cellToBoundary('8928308280fffff') // → [[lat0,lng0], [lat1,lng1], ..., [lat5,lng5]]
```
We feed that directly to `<Polygon positions={vertices} />`. The h3Index is already a string in the API response — no server changes needed for rendering.

### 2b. `TileDetailResponse` is missing `demandScoreMinutes`

The H3 refactor dropped `demandScoreMinutes` from `TileDetailResponse` — only `demandScoreOrders` remains. The demand heatmap and the sidebar edit control both need `demandScoreMinutes`. **Must add it back.**

### 2c. New `DemoController` for hex demand editing

The old `DemoController` referenced `Tile`, `TileDemandSnapshot`, `DaTileAssignment` — all deleted in the H3 refactor. Need a new `DemoController` backed by `Hex`, `HexDemandSnapshot`, `DaHexAssignment`.

Endpoints:
- `GET /api/demo/hexes/{hexId}/detail?date=` — hex + demand + assigned DA for the sidebar
- `PUT /api/demo/hexes/{hexId}/demand` — overwrite `demandScoreMinutes` for demo purposes

### 2d. `DemoSecurityConfig`

Need the same permissive CORS + no-auth config so the Vite dev server on `:5173` can hit the Spring server on `:8080`.

---

## 3. Implementation Plan

### Phase 1 — Backend: fix `TileDetailResponse` + new `DemoController`

**Step 1.1** — Add `demandScoreMinutes` to `TileDetailResponse`  
File: `grid/src/main/java/com/oneday/grid/dto/response/TileDetailResponse.java`  
Add field `double demandScoreMinutes` alongside the existing `demandScoreOrders`.  
Update `GridServiceImpl.getTileDetails` to populate it from `HexDemandSnapshot.getDemandScoreMinutes()`.

**Step 1.2** — Create `DemoHexDemandRequest`  
File: `grid/src/main/java/com/oneday/grid/dto/request/DemoHexDemandRequest.java`
```java
public record DemoHexDemandRequest(double demandScoreMinutes) {}
```

**Step 1.3** — Create `DemoController`  
File: `grid/src/main/java/com/oneday/grid/api/DemoController.java`  
Routes:
- `PUT /api/demo/hexes/{hexId}/demand` — finds the `HexDemandSnapshot` for today, deletes it, saves a new one with the overridden `demandScoreMinutes`
- `GET /api/demo/hexes/{hexId}/detail?date=` — returns a `HexDetail` record: hexId, h3Index, active, demandScoreMinutes, demandScoreOrders, serviceTimeMin, interStopTravelMin, bootstrapped, assignedDaId (from `DaHexAssignment`)

**Step 1.4** — Create `DemoSecurityConfig`  
File: `grid/src/main/java/com/oneday/grid/config/DemoSecurityConfig.java`  
Mirror the old config: allow all HTTP methods from `http://localhost:5173`, disable CSRF, permit all `/api/**`.

---

### Phase 2 — Frontend: scaffold demo-ui

**Step 2.1** — Copy `demo-ui/` directory structure from `f-M3-demo` into current branch (index.html, vite.config.js, tailwind.config.js, postcss.config.js, src/main.jsx, src/index.css). Keep the build scaffolding — only src files will differ.

**Step 2.2** — Add `h3-js` to `package.json` dependencies:
```json
"h3-js": "^4.1.0"
```
h3-js is the official Uber H3 JavaScript library — same index scheme as the Java library, just browser-side.

---

### Phase 3 — Frontend: `HexMap` component

**File**: `demo-ui/src/components/HexMap.jsx`

Core change vs old `GridMap`:
```jsx
import { cellToBoundary } from 'h3-js'
import { Polygon } from 'react-leaflet'

// Inside the render:
const vertices = cellToBoundary(hex.h3Index) // [[lat, lng], ...]
<Polygon
  positions={vertices}
  pathOptions={{ fillColor, color: isSelected ? '#1d4ed8' : '#475569', weight: isSelected ? 2 : 0.5, fillOpacity }}
  eventHandlers={{ click: () => onHexClick(hex.id) }}
>
  <Tooltip sticky>{hex.h3Index.slice(0, 10)}… · {Math.round(hex.demandScoreMinutes)} min</Tooltip>
</Polygon>
```

Same `demandColor(minutes, max)` gradient function (green → red).  
Same `hashDaColor(daId)` for territory mode.  
Map center defaults to the selected city's coordinates.

City center coordinates:
| City | Center |
|---|---|
| Delhi | `[28.6139, 77.2090]` |
| Mumbai | `[19.0760, 72.8777]` |
| Bangalore | `[12.9716, 77.5946]` |
| Hyderabad | `[17.3850, 78.4867]` |
| Chennai | `[13.0827, 80.2707]` |

---

### Phase 4 — Frontend: update all components and API

**`gridApi.js`** — two changes:
- `fetchTileDetail`: hits `GET /api/demo/hexes/{hexId}/detail`
- `updateTileDemand`: hits `PUT /api/demo/hexes/{hexId}/demand`
- All other calls are identical

**`App.jsx`** — changes:
- Add `cityCode` state with dropdown (Delhi default)
- Pass city center to `HexMap` for map re-centering
- `assignmentMap` keys on `hexId` (from `a.hexId` not `a.tileId`)
- `proposal.regions` uses `hexIds` not `tileIds`
- `proposal.understaffedHexIds` not `understaffedTileIds`
- Active count: `tiles.filter(t => t.active).length` — identical

**`HexPanel.jsx`** (renamed from `TilePanel`) — changes:
- Show `hex.h3Index` (first 15 chars) instead of `R{row} C{col}`
- All demand editing logic identical
- Toggle active/inactive: same PATCH endpoint

**`ProposalPanel.jsx`** — one field rename:
- `proposal.understaffedHexIds` instead of `proposal.understaffedTileIds`
- `r.hexIds?.length` instead of `r.tileIds?.length`
- Everything else identical

**`DaControls.jsx`** — no changes  
**`Legend.jsx`** — no changes  
**`daColors.js`** — no changes

---

### Phase 5 — Polish

- Hexagon borders look naturally crisp at zoom 11–13 (resolution 8 hexes are ~460m — visible without being too small)
- Hover: slightly increase fill opacity on mouseover
- If the grid hasn't been initialized, show a clear "Run POST /api/grid/admin/init?cityCode=delhi first" message
- Stale banner is identical to old demo

---

## 4. File change summary

### New / modified backend files
| File | Status |
|---|---|
| `grid/dto/response/TileDetailResponse.java` | Modify — add `demandScoreMinutes` |
| `grid/service/impl/GridServiceImpl.java` | Modify — populate `demandScoreMinutes` in `getTileDetails` |
| `grid/dto/request/DemoHexDemandRequest.java` | New |
| `grid/api/DemoController.java` | New |
| `grid/config/DemoSecurityConfig.java` | New |

### New / modified frontend files
| File | Status |
|---|---|
| `demo-ui/package.json` | New (add h3-js dep) |
| `demo-ui/src/main.jsx` | New (scaffold) |
| `demo-ui/src/index.css` | New (copy from old demo) |
| `demo-ui/src/App.jsx` | New (city selector + hexId field names) |
| `demo-ui/src/api/gridApi.js` | New (hex endpoints) |
| `demo-ui/src/components/HexMap.jsx` | New (Polygon + cellToBoundary) |
| `demo-ui/src/components/HexPanel.jsx` | New (h3Index display) |
| `demo-ui/src/components/ProposalPanel.jsx` | New (hexIds field names) |
| `demo-ui/src/components/DaControls.jsx` | Copy unchanged |
| `demo-ui/src/components/Legend.jsx` | Copy unchanged |
| `demo-ui/src/utils/daColors.js` | Copy unchanged |

---

## 5. How to run

```bash
# Terminal 1 — Spring Boot
mvn spring-boot:run -pl grid

# Terminal 2 — Vite dev server
cd demo-ui && npm install && npm run dev
```

Then:
1. `POST http://localhost:8080/api/grid/admin/init?cityCode=delhi` — initialize the grid (if not already done)
2. Open `http://localhost:5173`

---

## 6. What's genuinely new vs the rect demo

- **Hexagons on the map** — the most visible change; hex tessellation looks much more professional than a rectangular grid
- **Multi-city dropdown** — pick any of the 5 initialized cities
- **No row/col concept** — tiles are identified by their H3 index (globally unique, human-readable in hex notation)
- **Complete polyfill coverage** — no gaps between tiles; the entire city boundary is filled
