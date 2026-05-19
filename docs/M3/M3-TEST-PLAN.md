# M3 Grid Module — Test Plan (Phase 9)

## Context

65 unit tests across 8 files already pass (Phases 1–8). This plan covers everything still needed to give full confidence before demo and production use.

**Already covered (do not rewrite):**

| File | Tests |
|------|-------|
| `OrToolsSmokeTest` | 1 — OR-Tools native lib loads |
| `BfsAssignmentServiceImplTest` | 7 — greedy BFS seeding, expansion, understaffing |
| `ContiguityValidatorTest` | 12 — graph connectivity checks |
| `CpSatAssignmentServiceImplTest` | 6 — CP-SAT solve, infeasible fallback |
| `DemandScoringServiceImplTest` | 7 — bootstrap mode, M4 data, fallback |
| `GridServiceImplTest` | 8 — cache load, serviceability, tile-at math |
| `IntradayLoadScoreServiceImplTest` | 9 — update, severity thresholds, reset |
| `ProposalServiceImplTest` | 15 — approve, reject, override, tile-share |

**Total already: 65 tests**

---

## Phase A — New unit tests for untested services (no DB, no Kafka)

These are pure Mockito unit tests. Fast, no infrastructure needed.

### A1 — `GridReplanServiceImplTest`

**File:** `service/impl/GridReplanServiceImplTest.java`

The replan logic was extracted in Phase 8. Zero test coverage today.

| Test | What it verifies |
|------|----------------|
| `replan_withFreshMatrix_usesCpSat` | OSRM matrix exists and is fresh → `adjacencySource = OSRM`, CP-SAT called |
| `replan_withStaleMatrix_usesGeometricFallback` | Matrix rows older than 45 days → geometric 4-connectivity built, proposal flagged `GEOMETRIC_FALLBACK` |
| `replan_withNoMatrix_usesGeometricFallback` | No `tile_travel_time` rows at all → same fallback path |
| `replan_withNoDas_proposalHasZeroRegions` | Empty `daIds` list → proposal persisted with zero regions, all tiles understaffed |
| `replan_returnsProposalResponse` | Return value is the `ProposalResponse` from `proposalService.getProposal(proposal.getId())` |

**~5 tests**

---

### A2 — `OsrmMatrixServiceImplTest`

**File:** `service/impl/OsrmMatrixServiceImplTest.java`

Already used by other services but never directly tested.

| Test | What it verifies |
|------|----------------|
| `buildMatrix_2tiles_callsOsrmWithCorrectCoords` | 2 active tiles → OSRM client called with 2×2 coordinate list |
| `buildMatrix_emptyTileList_returnsEmptyEdges` | No active tiles → returns empty list without calling OSRM |
| `buildMatrix_osrmReturnsSymmetricMatrix_edgesCreated` | 3×3 duration matrix → 6 directed `TileEdge` objects (i≠j) with correct travel seconds |
| `buildMatrix_osrmReturnsNullDuration_edgeSkipped` | Null cell in OSRM response → that directed edge is not created |

**~4 tests**

---

### A3 — `NightlyReplanJobTest`

**File:** `batch/NightlyReplanJobTest.java`

Job now delegates to `GridReplanService` — test the orchestration layer, not the algorithm.

| Test | What it verifies |
|------|----------------|
| `run_iteratesAllConfiguredCities` | 3 cities in `GridProperties.cities` → `gridReplanService.replan()` called 3 times |
| `run_noDasFromRoster_stillCallsReplan` | `DaRosterPort` returns empty list → replan called with empty list (not skipped) |
| `checkEscalation_noApprovedProposal_logsWarning` | `findByCityIdAndValidForDateAndStatus` returns empty → log.warn fired |
| `applyFallbackIfNeeded_noApprovedProposal_copiesYesterdayAssignments` | No APPROVED proposal at 07:00 → yesterday's ACTIVE assignments cloned into new MANUAL proposal |
| `applyFallbackIfNeeded_approvedProposalExists_doesNothing` | APPROVED proposal exists → no cloning |

**~5 tests**

---

### A4 — `IntradayMonitorJobTest`

**File:** `batch/IntradayMonitorJobTest.java`

| Test | What it verifies |
|------|----------------|
| `monitor_tileExceedsWarningThreshold_incrementsHysteresisCounter` | Load score WARNING → counter goes from 0 to 1 |
| `monitor_tileExceedsCriticalSustained_firesOverloadAlert` | Load score CRITICAL sustained for required ticks → `TileOverloadAlertProducer.send()` called |
| `monitor_tileFallsBelowThreshold_resetsCounter` | Tile drops to OK → hysteresis counter reset to 0 |
| `monitor_tileWarningNotSustained_noAlert` | Only 1 of 3 required ticks exceeds threshold → no alert |

**~4 tests**

---

### A5 — `NoDaAlertProducerTest` + `TileOverloadAlertProducerTest`

**File:** `events/NoDaAlertProducerTest.java`, `events/TileOverloadAlertProducerTest.java`

| Test | What it verifies |
|------|----------------|
| `send_happyPath_callsKafkaTemplateWithCorrectTopic` | `kafkaTemplate.send(KafkaTopics.NO_DA_ALERT, tileId, event)` called |
| `send_kafkaTemplateThrows_doesNotPropagateException` | `kafkaTemplate.send()` throws → method returns without throwing (silent fail) |
| (same two tests for `TileOverloadAlertProducer`) | — |

**~4 tests (2 per file)**

---

**Phase A total: ~22 new unit tests**

---

## Phase B — Controller slice tests (`@WebMvcTest`)

No database. Spring MVC layer only. Mocks all services. Verifies HTTP status codes, path/query param binding, request body deserialization, response JSON shape.

### B1 — `GridControllerTest`

**File:** `api/GridControllerTest.java`

| Test | Endpoint | What it verifies |
|------|----------|----------------|
| `getTiles_knownCity_returns200WithTileList` | `GET /api/grid/delhi/tiles?date=2026-05-20` | 200, JSON array, rowIdx/colIdx/swLat present |
| `getTiles_unknownCity_returns404` | `GET /api/grid/atlantis/tiles` | 404 response |
| `setTileActive_validRequest_returns200` | `PATCH /api/grid/delhi/tiles/{id}/active?active=false` | 200, service called with `active=false` |
| `getLoadScore_knownTile_returns200WithSeverity` | `GET /api/grid/delhi/tiles/{id}/load-score` | 200, severity field present |
| `getVertices_returns200` | `GET /api/grid/delhi/vertices` | 200, list returned |
| `getAssignments_returns200` | `GET /api/grid/delhi/assignments?date=2026-05-20` | 200, list returned |
| `getServiceability_knownPincode_returnsServiceableTrue` | `GET /api/grid/delhi/serviceability?pincode=110001` | 200, `"serviceable":true` |
| `getServiceability_unknownPincode_returnsServiceableFalse` | `GET /api/grid/delhi/serviceability?pincode=999999` | 200, `"serviceable":false` |
| `getTileAt_validCoords_returns200` | `GET /api/grid/delhi/tile-at?lat=28.6&lon=77.2` | 200, rowIdx/colIdx present |
| `replan_validBody_returns201` | `POST /api/grid/delhi/replan` with `ReplanRequest` body | 201, proposal ID in response |
| `adminInit_returns201` | `POST /api/grid/admin/init` | 201, service called |

**~11 tests**

---

### B2 — `ProposalControllerTest`

**File:** `api/ProposalControllerTest.java`

| Test | Endpoint | What it verifies |
|------|----------|----------------|
| `getProposal_knownId_returns200` | `GET /api/proposals/{id}` | 200, proposal fields present |
| `getProposal_unknownId_returns404` | `GET /api/proposals/{unknownId}` | 404 |
| `listProposals_returns200WithList` | `GET /api/proposals?cityId=...&date=...` | 200, array |
| `approve_returns200` | `POST /api/proposals/{id}/approve` with `ApproveRequest` | 200 |
| `reject_returns200` | `POST /api/proposals/{id}/reject` with `ProposalRejectRequest` | 200 |
| `editRegion_returns200` | `PUT /api/proposals/{id}/regions/{daId}` with `RegionEditRequest` | 200 |
| `intradayReassignment_returns201` | `POST /api/proposals/intraday-reassignment` | 201 |
| `approveReassignment_returns200` | `POST /api/proposals/{id}/approve-reassignment` | 200 |
| `tileShare_returns201` | `POST /api/proposals/tile-share` | 201 |
| `approveTileShare_returns200` | `POST /api/proposals/{id}/approve-tile-share` | 200 |

**~10 tests**

---

**Phase B total: ~21 new controller tests**

---

## Phase C — Integration tests (TestContainers + real PostgreSQL)

These spin up a real Postgres 16 container, run all Flyway migrations, and test the full stack from service to DB. Slow (~30s startup) but the highest-value tests — they catch things unit tests never will.

**Setup:** One shared `@SpringBootTest` base class annotated `@Testcontainers` with a static `@Container PostgreSQLContainer`. Reuse the same container across all test classes via `@DynamicPropertySource`.

### C1 — `GridServiceIntegrationTest`

**File:** `service/impl/GridServiceIntegrationTest.java`

| Test | What it verifies |
|------|----------------|
| `initializeGrid_persistsGrid_tilesAndVertices` | After `initializeGrid()`, DB has 1 Grid row, M×N Tile rows, (M+1)×(N+1) GridVertex rows |
| `initializeGrid_idempotent_doesNotDuplicate` | Calling `initializeGrid()` twice → still 1 Grid row (upsert or skip logic) |
| `checkServiceability_afterInit_knownPincodeReturnsTrue` | Pincode from city YAML → `serviceable = true` |
| `getTileAt_afterInit_returnsCorrectRowCol` | Coords inside a known tile → row/col matches expected from tile math |
| `setTileActive_persistsChange` | `setTileActive(tileId, false)` → `tile.active = false` in DB |
| `getActiveAssignments_afterApproval_returnsActiveAssignments` | Approved proposal → assignments retrieved by cityId + date |

**~6 tests**

---

### C2 — `ProposalServiceIntegrationTest`

**File:** `service/impl/ProposalServiceIntegrationTest.java`

| Test | What it verifies |
|------|----------------|
| `approve_setsProposalStatusApproved` | `approveProposal(id, reviewerId)` → `status = APPROVED` in DB |
| `reject_setsProposalStatusRejected` | `rejectProposal(id, reason)` → `status = REJECTED` in DB |
| `approve_supersediesPreviousApprovedProposal` | City has existing APPROVED proposal → approving new one sets old to SUPERSEDED |
| `editRegion_updatesAssignmentProposalRegion` | `editRegion(proposalId, daId, newTileIds)` → region row updated, old assignments superseded |
| `intradayReassignment_createsIntradayProposal` | `createIntradayReassignment()` → new proposal with type `INTRADAY_OVERRIDE`, status `PROPOSED` |
| `approveReassignment_activatesNewAssignments` | Approving intraday reassignment → new `DaTileAssignment` rows become `ACTIVE`, old become `SUPERSEDED` |

**~6 tests**

---

### C3 — `BfsAssignmentServiceIntegrationTest`

**File:** `service/impl/BfsAssignmentServiceIntegrationTest.java`

These use a real DB to verify persistence, not algorithm correctness (unit tests handle that).

| Test | What it verifies |
|------|----------------|
| `computeProposal_persistsProposalWithRegionsAndAssignments` | BFS output → proposal + N region rows + M assignment rows all in DB, FK integrity holds |
| `computeProposal_allTilesAssigned_noUnderstaffedRecorded` | Sufficient DAs → `understaffed_tile_ids` is empty array in DB |
| `computeProposal_insufficientDas_understaffedTilesRecorded` | 1 DA, 10 tiles → some tiles in `understaffed_tile_ids` JSONB column |
| `uniqueConstraintEnforced_duplicateAssignment_throws` | Inserting duplicate `(proposal_id, da_id, tile_id, valid_date)` → DB constraint violation |

**~4 tests**

---

### C4 — `GridReplanServiceIntegrationTest`

**File:** `service/impl/GridReplanServiceIntegrationTest.java`

The highest-value end-to-end test — exercises the full replan pipeline against a real DB.

| Test | What it verifies |
|------|----------------|
| `replan_endToEnd_createsProposalWithCorrectStatus` | Full pipeline: demand → adjacency → CP-SAT → DB → `ProposalResponse` returned with status `PROPOSED` |
| `replan_geometricFallback_proposalFlagged` | No `tile_travel_time` rows → `adjacency_source = GEOMETRIC_FALLBACK` in proposal row |
| `replan_returnsProposalResponseWithTileAssignments` | Response contains assignment list with correct tile IDs matching what was persisted |

**~3 tests**

---

**Phase C total: ~19 new integration tests**

---

## Phase D — Kafka slice tests (`EmbeddedKafka`)

Uses `@EmbeddedKafka` from `spring-kafka-test`. No broker needed, runs in-process. Tests actual serialization/deserialization round-trip.

**Dependency to add to `grid/pom.xml`:**
```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

### D1 — `NoDaAlertProducerIntegrationTest`

**File:** `events/NoDaAlertProducerIntegrationTest.java`

| Test | What it verifies |
|------|----------------|
| `send_publishesMessageToCorrectTopic` | `NoDaAlertProducer.send()` → message lands on `grid.no_da_alert` topic with correct key (tileId) and deserializable payload |
| `send_payloadRoundTrips` | Serialized then deserialized `NoDaAlertEvent` has same cityId, tileId, validDate values |

**~2 tests**

---

### D2 — `TileOverloadAlertProducerIntegrationTest`

**File:** `events/TileOverloadAlertProducerIntegrationTest.java`

| Test | What it verifies |
|------|----------------|
| `send_publishesMessageToCorrectTopic` | Message lands on `grid.tile_overload_alert`, key = tileId |
| `send_severityFieldPreservedInPayload` | `severity = "CRITICAL"` survives serde round-trip |

**~2 tests**

---

### D3 — `TileQueueDepthConsumerTest`

**File:** `events/TileQueueDepthConsumerTest.java`

Consumer has `autoStartup=false` so we manually start it in the test.

| Test | What it verifies |
|------|----------------|
| `onQueueDepth_updatesLoadScoreService` | Send a `TileQueueDepthEvent` to `orders.tile_queue_depth` → `loadScoreService.updateQueueDepth()` called with correct tileId and unservedOrders count |
| `onQueueDepth_multipleEvents_lastWriteWins` | Two events for same tile → second count wins (ConcurrentHashMap semantics) |

**~2 tests**

---

**Phase D total: ~6 new Kafka tests**

---

## Phase E — WireMock tests for OSRM

Tests the actual HTTP integration with OSRM, isolated by WireMock. Already have `wiremock` dep in `grid/pom.xml`.

### E1 — `OsrmClientTest`

**File:** `service/osrm/OsrmClientTest.java`

| Test | What it verifies |
|------|----------------|
| `getTable_2x2matrix_parsesCorrectly` | WireMock stubs `/table/v1/driving/...` → `OsrmTableResponse.durations[0][1]` equals expected seconds |
| `getTable_osrmReturns500_throwsRuntimeException` | 500 response → exception propagated (not swallowed) |
| `getTable_coordinatesFormattedCorrectly` | Verifies WireMock received URL with `lon,lat` order (OSRM expects lon first) |
| `getTable_largeMatrix_allCellsParsed` | 5-tile grid → 5×5 = 25 cells parsed without loss |

**~4 tests**

---

**Phase E total: ~4 new OSRM tests**

---

## Summary table

| Phase | Scope | New tests | Infrastructure |
|-------|-------|-----------|---------------|
| Already done | Unit (Phases 1–8) | 65 | None |
| **A** | Unit — untested services + batch jobs | ~22 | None |
| **B** | Controller slice (`@WebMvcTest`) | ~21 | None |
| **C** | Integration (TestContainers PostgreSQL) | ~19 | Postgres container |
| **D** | Kafka slice (`EmbeddedKafka`) | ~6 | Embedded broker |
| **E** | WireMock OSRM | ~4 | WireMock |
| **Total** | | **~137** | — |

---

## Execution order

Do phases in order A → B → C → D → E. A and B are the fastest (pure in-process) and will catch the most regressions quickly. C is the highest-value but slowest — save it for after A+B are green. D and E are small and can be done last.

Run after each phase:
```bash
mvn test -pl grid
```

For C only (TestContainers needs Docker running):
```bash
mvn test -pl grid -Dtest="*IntegrationTest"
```

---

## What is intentionally NOT tested

- `NoOpDaRosterPort` — it logs and returns empty, nothing to assert
- `DaRosterPort` interface — tested implicitly via `NightlyReplanJobTest`
- `KafkaTopics` constants — these are string literals, no logic to test
- `GridInitializationJob` / `OsrmMatrixRefreshJob` scheduling triggers — `@Scheduled` fire time is an ops concern, not a unit concern; the underlying service methods are covered in C1 and E1
