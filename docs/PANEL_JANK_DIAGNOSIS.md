# PANEL JANK DIAGNOSIS — morning measurement recipe

**Status:** diagnosis tooling shipped on branch `diag-panel-jank`
(`0692a56` tools/jank_report.py, `707b17d` in-app counters). **No fix
attempted** — per the owner rule, no fix without a diagnosis. This page
is the measurement plan for the morning device pass.

**The complaint:** the editor stutters while the adjustment panel moves
DOWN (collapse/downward settle). The preview's SurfaceView (GPU window
path) resizes as the panel flexes the free region, and a resize can
reallocate the window surface's buffers on the compositor — the prime
suspect. The shipped `freezeSurfaceSize` mechanism (commit c22eb37
family, `ClipEditorScreen` line: `freezeSurfaceSize =
panelDrag.isAnimationRunning`) pins the SurfaceView buffers with
`setFixedSize` for the whole animation so only ONE layout-sized resize
should follow at settle; **whether that actually holds is exactly what
the counters below measure.**

---

## What shipped tonight

1. `tools/jank_report.py` (+ `tools/jank_report_testdata/synthetic.csv`)
   — a Perfetto trace_processor SQL consumer: per-frame verdict table
   for DROPPED frames (which stage ate the deadline: UI /
   RenderThread / GL / SurfaceFlinger / TIE / waker-fallback) + a
   guilty-thread histogram. Self-test:
   `python3 tools/jank_report.py --selftest` → exit 0 (the synthetic
   fixture: 3 dropped frames across UI/RenderThread/GL + 1 SF-late
   dropped frame + 1 tie + 1 missing-slices waker fallback + 1 on-time
   control).

2. In-app DEBUG counters (`PanelAnimTrace`, wired in
   `ClipEditorScreen`): one `ritim-trace` line per panel animation,
   e.g.
   `panel anim from=EXPANDED to=COLLAPSED dur_ms=231 renders=4 resizes=0`
   — `renders=` preview render requests offered inside the animation
   window, `resizes=` SurfaceView `surfaceChanged` callbacks inside the
   same window. The window is exactly `panelDrag.isAnimationRunning`,
   the same signal `freezeSurfaceSize` reads, so `resizes=0` means the
   freeze held and `resizes>0` indicts it directly.

---

## Morning recipe (each leg independent; run A first — it is free)

### Leg A — in-app counters, zero external tools (fully shipped)

Build debug, install, then watch the stable tag:

```
adb logcat -c
adb logcat -s ritim-trace
```

Then, on the phone (portrait editor, GPU preview path — check the
badge says GPU), repeat 5–10 times each:

- swipe the panel handle DOWN from EXPANDED to COLLAPSED (the reported
  janky direction);
- swipe UP from COLLAPSED to EXPANDED (the control direction);
- one category tap open (COLLAPSED → NORMAL, programmatic open).

Read the burst lines:

```
panel anim from=EXPANDED to=COLLAPSED dur_ms=210 renders=4 resizes=0
```

- `resizes=0` on every downward line → **the freeze holds; the
  buffer-realloc suspect is cleared** and the jank is inside the render
  pipeline / Compose layout → Leg B tells which stage.
- `resizes>=1` during the animation window (a `surfaceChanged WxH`
  ritim-trace line stamped BETWEEN anim start and end) → **the freeze
  failed**: every resize reallocated buffers mid-animation. The next
  diagnosis step is WHERE the resize came from (the existing
  `surfaceChanged WxH` lines name the sizes).
- `renders=` bigger than ~3–4 on a 200 ms settle → the renders are
  compounding the animation; note the number for Leg B's model of how
  busy the pipe was during the drop.

### Leg B — Perfetto frame timeline → jank_report verdicts

**Get the trace** (TBD spellings → marked):

```
# 1. generate the query (REAL — this repo's script):
python3 tools/jank_report.py --print-query > /tmp/jank_query.sql

# 2. record a trace while the panel is dragged down (perfetto CLI
#    shape — SABAH DOĞRULANACAK: exact on-device perfetto flag order
#    and the frame-timeline data source name on this phone's build):
adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/panel.pbf <<'EOF'
buffers: { size_kb: 65536 }
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      atrace_categories: "view gfx sched binder"
      atrace_apps: "com.ritim.ritimcraw"
    }
  }
}
data_sources: {
  config { name: "android.surfaceflinger.frame_timeline" }
}
duration_ms: 10000
EOF

# 3. pull it (REAL):
adb pull /data/misc/perfetto-traces/panel.pbf /tmp/panel.pbf

# 4. run the pinned SQL and land a CSV (trace_processor CLI shape —
#    SABAH DOĞRULANACAK: whether `-q` prints a clean CSV or a text
#    table; if not CSV, drop into the interactive shell
#    (trace_processor /tmp/panel.pbf -i) and export from there —
#    the script's parser matches columns BY NAME either way):
trace_processor /tmp/panel.pbf -q /tmp/jank_query.sql > /tmp/out.csv

# 5. verdict table (REAL — this repo's script):
python3 tools/jank_report.py --input /tmp/out.csv
```

**Setup:** start the trace first, then swipe the panel down 3–4 times
inside the 10 s window. On a 60 Hz display the expected duration is
16.7 ms; the frame timeline labels misses `Dropped Frame` /
`Dropped Frame vsync deadline` — the exact `jank_type` string column on
this trace_processor build is marked in the script docstring.

**Read the output:** the per-frame verdict table names the stage whose
slice ate the biggest clipped span of the missed frame's expected
window; the histogram sums the burst. The verdict semantics are pinned
at the top of `tools/jank_report.py`:

- `UI` → main-thread work (Compose relayout of the preview + panel
  re-layout each animation frame, or a render publisher blocking);
- `RenderThread` → the androidx RenderThread draw/record path;
- `GL` → our own GPU window/engine worker (texture upload, present
  wait);
- `SurfaceFlinger` → the compositor missed the deadline — the display
  was contended and the app was on time;
- `TIE(a|b)` / `stage=X` / `UNKNOWN` → honest fallbacks.

**If the trace is noisy** run the whole leg on a paused clip (the
playback loop is OFF) — the panel animation is then the ONLY activity
in the pipe.

### Leg C — quick cross-checks (REAL commands)

```
# RenderThread jank stats per surface:
adb shell dumpsys gfxinfo com.ritim.ritimcraw framestats

# Reset between legs:
adb shell dumpsys gfxinfo com.ritim.ritimcraw reset

# The app's own per-render line (one per completed render — check the
# panel-animation renders' ms= and statsMode= against Leg A's counts):
adb logcat -d -s ritim-trace
```

`dumpsys gfxinfo framestats` gives the raw per-frame timestamps of the
last 120 frames; the column meaning (IntendedVsync … FrameCompleted) is
the platform standard — the same shading Leg B gives but from the
platform's own accountant, no trace needed.

---

## Verdict → next step (the morning decision table)

| Leg A counts | Leg B verdict | Next step |
|---|---|---|
| `resizes=0` | `UI` | the Compose re-layout of the flexing preview/panel is missing the deadline → candidate later fix: layout-phase panel height read (already exists, 2df112b) extended to the SurfaceView wrapper, or Choreographer-level frame pacing — comes AFTER the numbers |
| `resizes=0` | `RenderThread` | the draw itself is over-budget while animating → record the GPU frame profile segmentation in a follow-up |
| `resizes=0` | `GL` | our window present / texture wait dominates → inspect the engine thread in Perfetto's UI (slice names in Leg B CSV) |
| `resizes=0` | `SurfaceFlinger` | the app was on time; display contention → out of the app's hands; try re-testing with fewer composable-layers during animation |
| `resizes>=1` | any | the freeze failed: hunt where the resize comes from (the `surfaceChanged WxH` lines + the tag R.id.preview_surface_frozen transitions) — the freeze path is the one allowed fix target |

WARNING: no fix tonight (and none landed). The above table is plan
only; the morning numbers pick the actual next branch.

---

## SABAH DOĞRULANACAK (marked everywhere; one place to read)

1. `adb shell perfetto --txt` — is the flag spelled `-c - --txt -o` on
   this phone's build; and is the on-device config stdin syntax
   accepted from an adb heredoc:
   **verify once, then fix the config block above;** the data source
   name `android.surfaceflinger.frame_timeline` is the current format —
   an older firmware may not expose it → check with:
   `adb shell perfetto --query-data-sources` if that subcommand exists
   on this build.
2. `trace_processor -q file.sql` output: CSV directly, or a text table
   that needs `--interactive` + `.save`? The script's parser is
   column-name driven — if the shell prints a text table, the CSV can
   come from `adb shell` redirecting through the python API
   (`trace_processor --httpd` + the /query endpoint is another route).
3. trace_processor binary on this machine: not on PATH in this
   worktree (`which trace_processor` empty). Get it in the morning
   from the Perfetto docs quickstart (`get_trace_processor` /
   brew/artifact link at perfetto.dev, network required), or open the
   trace at https://ui.perfetto.dev and paste JANK_QUERY in the
   "Query (SQL)" pane — the .csv export there is guaranteed real, the
   web UI is the zero-install path of this leg.
4. fence times: the panel spring in the app is a critically damped
   spring (settleSpec, EditorPanel.kt:61) — calibrate `dur_ms` against
   it (should read 200-400 ms from start to settled for a normal
   drag-release; a much longer window signals the settle restarted or
   was re-targeted mid-flight, worth noting before reading renders=).
5. `math`: PanelAnimTrace `dur_ms` is wall-clock, not animation —
   fine, it bounds the window.

## The script's SQL (real — this is what the recipe runs)

The query below is the pinned `JANK_QUERY` constant from
`tools/jank_report.py` (commit 0692a56) — the CSV contract the parser
matches is its SELECT column NAMES; produce the CSV with any
trace_processor route, the parser is name-driven:

```sql
-- One row per (frame x contributing app/sf slice).  Frames come from
-- the frame timeline (actual vs expected); contributing slices come
-- from `slice` joined to thread/process, clipped to the expected window.
WITH frames AS (
  SELECT
    f.id  AS frame_fff,
    f.name AS frame_name,
    f.ts  AS frame_ts,
    f.dur AS actual_dur,
    f.jank_type AS jank_type,
    (SELECT e.dur FROM expected_frame_timeline e
      WHERE e.ts = f.ts LIMIT 1) AS expected_dur
  FROM actual_frame_timeline f
),
slices AS (
  SELECT
    s.name AS slice_name,
    t.name AS slice_thread,
    p.name AS slice_process,
    s.ts  AS slice_ts,
    s.dur AS slice_dur
  FROM slice s
  JOIN thread_track tt ON s.track_id = tt.id
  JOIN thread t ON tt.utid = t.utid
  LEFT JOIN process p ON t.upid = p.upid
)
SELECT
  frames.frame_fff,
  frames.frame_name,
  frames.frame_ts,
  frames.actual_dur,
  frames.jank_type,
  frames.expected_dur,
  slices.slice_name,
  slices.slice_thread,
  slices.slice_process,
  slices.slice_ts,
  slices.slice_dur,
  '' AS waker_type,
  '' AS waker_thread,
  '' AS waker_uiname,
  frames.frame_ts + frames.expected_dur AS expected_deadline_ns
FROM frames
LEFT JOIN slices
  ON slices.slice_ts >= frames.frame_ts
 AND slices.slice_ts <  frames.frame_ts + frames.expected_dur
ORDER BY frames.frame_ts;
```

The table/column names inside (`actual_frame_timeline`,
`expected_frame_timeline`, `jank_type`) are the standard Perfetto
frame-timeline spellings as of the script's writing — **SABAH
DOĞRULANACAK against the installed trace_processor version** (edit
JANK_QUERY only; the parsing/reporting code is name-driven, see the
script docstring).
