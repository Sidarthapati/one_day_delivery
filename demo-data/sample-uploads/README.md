# Demo sample uploads

Example `.xlsx` sheets for the **bulk-destinations upload** on the :8080 customer console
(`POST /api/v1/bulk/upload`, "📤 Upload Destinations"). They are **manually uploaded through the
browser** — no code reads them by path — so they're plain demo inputs you can edit freely.

Both follow the fixed bulk-upload template (download the canonical one via "⬇️ Destinations Template"
on the booking card, or `GET /api/v1/bulk/template`): 13 headers `receiver_name … declared_value_inr`,
one destination per row, all sharing the **pickup pin** you set on the map before uploading.

| File | Rows | What it's for |
|------|------|---------------|
| `drop20.xlsx` | 20 **Delhi** destinations (`110001…110020`) | Last-mile **drop** demo. Upload with a **non-Delhi pickup pin** (e.g. Mumbai) → 20 real Mumbai→Delhi shipments. Then drive them with **📦 Dispatch drops** → **Run the day** → **🏠 Auto-verify deliveries** on the Execution tab. |
| `picks_20.xlsx` | 20 destinations | First-mile **pickup** demo — bulk-book 20 shipments from one pickup, which M5 assigns to DAs. |

> Tip: the **🌐 Spread pickups / Spread drops** buttons on the Execution tab book equivalent shipments
> programmatically (one per DA territory) without uploading a sheet — use those for a multi-DA demo,
> and these sheets when you want to show the customer-facing bulk-upload path itself.
