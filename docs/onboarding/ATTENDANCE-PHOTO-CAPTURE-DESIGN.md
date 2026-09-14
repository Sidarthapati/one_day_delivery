# Attendance Selfie Capture — Design

> Status: **DESIGN / proposal.** No code yet. Source: Knostics KT session (`da_onboarding_knostics.mov`).
> Effort: **small** — the geofence, the manager-approval loop, and the object-storage upload plumbing all
> already exist. The only genuinely new thing is capturing and storing a **selfie** at check-in (and,
> optionally, at check-out).

## 1. Why

Amazon/Knostics require every delivery-associate (DA) attendance mark to carry a **selfie** taken at the
station, alongside the geofenced location. Our attendance is otherwise equivalent — geofenced, station-manager
approved — but **captures no photo**. Adding the selfie closes the last gap and gives the station manager a
visual proof-of-presence to approve against (defeats "mark a friend present" abuse, which our GPS-integrity
work already partly addresses).

## 2. Reference flow (from the video)

Knostics' KOMS portal, `Manage Attendance` module:

- **`Manage Attendance [<STATION>]`** (`/admin/user/associateattendance`) — a per-station table:
  `S.no · Name + Knostics-ID · Status (Working) · View · Duration · Date · Day · Login Time · Logout Time ·
  Radius (InDoor/Outdoor) · Login Lat/Long`. Sub-nav: Manage Attendance, Manage Leave Requests, Manage Weekly
  Off Requests, Manage Overtime.
  → screen: `screens/knostics-manage-attendance.jpg`
- **`Edit Partner Attendance`** (`/admin/user/edit_associate_attendance/…`) — the record the manager reviews:
  - *Partner Information:* Name, Knostics Id, Role, Station, **Login Image (selfie)**, **Logout Image (selfie)**,
    **Status = Approved** (dropdown), **Approved By** (manager name + id).
  - *Attendance Information:* Date, Day, Login Time, **Login Lat/Long**, Logout Time, **Logout Lat/Long**,
    Duration, **Last 15 Days Forgot Count**, Shift Name; plus a **Break Info** section.
  → screen: `screens/knostics-edit-partner-attendance-selfie.jpg`

Takeaway: attendance = **{ login selfie + login lat/long + login time }** and symmetrically for logout, with a
manager Approve/Reject and an "Approved By" audit. We already have everything except the two images.

## 3. What we have today

All in **dispatch (M5)**, with the alert inbox in **exceptions (M11)**:

| Piece | Location |
|---|---|
| Check-in / muster / present / absent | `dispatch/.../api/AttendanceController.java` |
| Geofence + auto-present logic | `dispatch/.../service/impl/AttendanceServiceImpl.java` (`checkIn`, `onGpsFix`) |
| Entity / table | `DaAttendance` → `da_attendance` (Flyway `V5_16`) |
| Method enum | `DaAttendanceMethod` = `AUTO_GEOFENCE | MANUAL_CHECKIN | MANAGER_PRESENT | MANAGER_ABSENT` |
| Muster row DTO | `dispatch/.../dto/response/AttendanceMusterEntry.java` |
| Check-in request | `dispatch/.../dto/request/AttendanceCheckInRequest.java` (`lat`, `lon`) |
| Config | `DispatchProperties.Attendance` (`radius-meters` = 500, per-city hub coords, cutoff) |
| GPS-integrity gating | `V5_19` flags on `da_gps_ping`; mocked/teleport/skew fixes already blocked |
| Alert inbox | exceptions M11 `attendance_alert` (`V11_6`) |

Endpoints today:

```
POST /dispatch/da/{daId}/attendance/check-in     # "I've arrived" → PRESENT if within 500 m, else 422
GET  /dispatch/da/{daId}/attendance/today
GET  /dispatch/attendance/muster?cityId&shift&date        # STATION_MANAGER
POST /dispatch/attendance/{daId}/present?date             # manager confirm
POST /dispatch/attendance/{daId}/absent?date&reason       # manager mark absent
```

Current `da_attendance` columns: `da_id, city_id, attendance_date, shift_type, status, method, detected_lat,
detected_lon, distance_m, marked_by_user_id, source_ping_at`. **No image column.**

**Reusable upload plumbing already in the repo** (this is why it's small):
- `common/.../port/ObjectStoragePort.java` — `presignPut(key, contentType, ttl)` / `presignGet` / `exists`;
  private R2 bucket, short-lived URLs; `isAvailable()` degrades gracefully.
- Working precedent to copy almost verbatim: **parcel-dimension evidence photos** —
  `orders/.../api/ParcelMeasurementController.java` presigns N upload slots, the client PUTs directly to R2,
  then submits the keys back. Mirror this shape for the selfie.
- Driver app (`oneday-driver-app`, RN/Expo) already bundles **`expo-camera`** (barcode-only today) and
  `expo-location`; the dimension-checker feature is the precedent that added camera-photo + presigned upload
  to that app.

## 4. Gap

1. No place to store the selfie key(s) on the attendance row.
2. No presign endpoint for an attendance selfie.
3. `checkIn` / `onGpsFix` don't accept or require an image key.
4. The muster / approval console doesn't show the photo (or lat/long) to the manager.
5. (Optional / stretch) no explicit check-out with a logout selfie, no "forgot count", no break tracking —
   Knostics has these; we can defer all but the login selfie.

## 5. Proposed design

### 5.1 Data — one migration
New Flyway migration in dispatch (**verify next free version** — recent branches used through ~`V5_22`; use the
next free, e.g. `V5_23`):

```sql
ALTER TABLE da_attendance
  ADD COLUMN login_image_key   TEXT,       -- R2 object key of the check-in selfie
  ADD COLUMN logout_image_key  TEXT;       -- optional, for a future check-out selfie
```

Text pointers only (the bytes live in R2), matching how `panDocUrl` and the parcel-measurement keys are stored.
No enum change is strictly required, but consider adding `SELFIE_CHECKIN` to `DaAttendanceMethod` if we want to
distinguish "arrived with photo" from the plain `MANUAL_CHECKIN`.

### 5.2 Backend — reuse the presign→upload→submit shape
1. **Presign** (new on `AttendanceController`):
   ```
   POST /dispatch/da/{daId}/attendance/selfie/upload-url   → { key, uploadUrl }   (DA role, self)
   ```
   Delegates to `ObjectStoragePort.presignPut(key, "image/jpeg", ttl)`. Key convention:
   `attendance/{cityId}/{attendanceDate}/{daId}/login-{uuid}.jpg`.
2. **Check-in carries the key**: add `selfieKey` to `AttendanceCheckInRequest`; in `AttendanceServiceImpl.checkIn`,
   after the geofence test passes, validate `ObjectStoragePort.exists(selfieKey)` and persist it to
   `login_image_key`. Make it **required** for `MANUAL_CHECKIN` (reject with 422 if absent); keep `AUTO_GEOFENCE`
   (pure GPS auto-present) photo-optional so the silent auto-present path still works.
3. **Manager view**: extend `AttendanceMusterEntry` with `loginLat/loginLon`, `distanceM`, and a **presigned GET
   URL** for the selfie (`ObjectStoragePort.presignGet(login_image_key)`), so the muster/approval console can show
   the photo next to Present/Absent. No new approval endpoint needed — `present`/`absent` already exist; the photo
   just informs the decision. (The existing `marked_by_user_id` already captures "Approved By".)

### 5.3 Driver app (`oneday-driver-app`)
- On "I've arrived": open `expo-camera` (front), capture selfie → `POST …/selfie/upload-url` → PUT the image to
  the returned URL → call check-in with `selfieKey`. Add the multipart/PUT upload helper to `src/api.ts` (today
  JSON-only) — the dimension-checker branch already did exactly this and is the reference.
- Keep the 30 s GPS heartbeat (`src/location.ts`) unchanged; auto-present stays photo-less.

### 5.4 Station console (`oneday-web`)
- In the attendance muster/approval screen, render the selfie thumbnail + login lat/long per row; clicking Present
  approves as today. (Mirrors Knostics `Edit Partner Attendance`.)

## 6. Build slices

1. **S1 — storage + backend (core):** migration (`login_image_key`), presign endpoint, `checkIn` accepts &
   validates `selfieKey`, muster DTO exposes presigned GET + lat/long. Unit tests around required-selfie 422.
2. **S2 — driver app:** camera capture + presigned upload + check-in wiring.
3. **S3 — station console:** show photo + location in muster; approve as today.
4. **S4 (optional, later):** check-out selfie (`logout_image_key`), duration, "forgot count", break info — full
   parity with Knostics `Edit Partner Attendance`.

## 7. Open questions

- **Q-A1:** Selfie mandatory for **manual** check-in only, or also require a periodic re-selfie for **auto-present**
  DAs? (Proposed: manual only; auto-present stays photo-less to keep the GPS path cheap.)
- **Q-A2:** Retention — how long do we keep attendance selfies in R2? (Propose a lifecycle rule, e.g. 90 days,
  matching other evidence photos.)
- **Q-A3:** Do we adopt Knostics' `Radius: InDoor/Outdoor` label? We already compute `distance_m`; "InDoor" is
  just `distance_m ≤ radius`. Cheap to surface.

## 8. Reference screens
- `screens/knostics-manage-attendance.jpg`
- `screens/knostics-edit-partner-attendance-selfie.jpg`
