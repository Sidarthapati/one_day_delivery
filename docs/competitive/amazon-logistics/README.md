# Amazon Logistics — Competitive Screen Study

Local, durable capture of the Amazon Logistics **delivery-partner app** walkthrough, used to
map the feature/data delta against our Godspeed / one_day_delivery platform.

## Source
- Video: `~/Downloads/Amazon_Logistics_No_Audio.mov` — 2126×1484 @ 60fps, ~51.6 min (51,634 frames).

## How the collection was built
1. **Scene detection** — `ffmpeg select='gt(scene,0.12)'` emitted one frame per screen transition
   (downscaled to 900px wide, JPEG q4) → `frames/` (152 raw scene frames).
2. **Perceptual dedup** — 16×16 dHash, Hamming ≤10 collapses near-identical scroll frames
   → `screens/` (137 distinct screens). The counts barely dropped across thresholds, confirming the
   video genuinely traverses ~135 distinct screens.

## Folders
- `frames/` — raw scene-change frames (`scene_NNNN.jpg`), pre-dedup.
- `screens/` — the curated collection, one image per distinct screen (`screen_NNN.jpg`). **This is the reference set.**
- `timed/` — reserved for a time-sampled safety net (unused; scene detection was sufficient).

## Analysis outputs
- `AMAZON-SCREEN-INVENTORY.md` — annotated catalog of every screen (purpose, UI, data, features).
- `GAP-ANALYSIS.md` — Amazon capability → our equivalent → Have / Partial / Missing, by theme.
- A visual HTML Artifact (published separately) embeds thumbnails next to each gap verdict.

## To re-extract from the video
```bash
ffmpeg -i ~/Downloads/Amazon_Logistics_No_Audio.mov \
  -vf "select='gt(scene,0.12)',scale=900:-1" -fps_mode vfr -q:v 4 frames/scene_%04d.jpg
```
