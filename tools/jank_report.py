#!/usr/bin/env python3
"""Panel-jank verdict tool: which thread missed the deadline on a DROPPED frame?

Given Perfetto trace_processor SQL output (the dedicated frame-timeline
query pinned below as JANK_QUERY), this script prints a per-frame verdict
table for DROPPED frames plus a guilty-thread histogram:

  verdict        | meaning (the stage that ate the frame's deadline)
  ---------------+---------------------------------------------------------
  UI             | the app's main/UI thread dominated (layout/compose/
                 | input jank inside the app);
  RenderThread   | androidx RenderThread (RecordFunctor/draw) dominated;
  GL             | the app's own GL/engine worker thread dominated (the
                 | GLES window present, texture waits, shader compile);
  SurfaceFlinger | the compositor dominated, or the app produced on time
                 | but SF queued/presented late (display contention, not
                 | the app's fault);
  TIE(a|b)       | two stages tied for the largest span — reported
                 | honestly, split evenly in the histogram;
  stage=<x>      | fallback when NO slice covered the expected window:
                 | the waker column (binder/gpu) names the blame;
  UNKNOWN        | no slices AND no waker information at all.

VERDICT RULE (documented, single-stage attribution): among all slices
that overlap the frame's expected deadline window [ts, ts+expected_dur),
the stage with the largest clipped span wins; ties produce TIE(a|b).

INPUT FORMAT — EXACT CONTRACT: a CSV with a header row whose names match
EXPECTED_COLUMNS (matched BY NAME via HEADER_ALIASES, so a few trace
series names are tolerated; unknown extra columns are ignored; a missing
column degrades to its default). One row per (frame x contributing
slice): rows that belong to the same frame share every frame column and
differ only in the slice_* columns; a frame with no contributing slices
has exactly one row with the slice columns empty. Generate it by saving
JANK_QUERY to a file and running:

    python3 tools/jank_report.py --print-query > jank_query.sql
    trace_processor -q jank_query.sql trace.pbf > out.csv
    python3 tools/jank_report.py --input out.csv

The exact trace_processor column spellings of the frame-timeline tables
vary by build; the query below follows the standard `actual_frame_timeline`
/ `expected_frame_timeline` / `slice` shapes and is marked "sabah
doğrulanacak": if the installed trace_processor complains about a column
name, edit JANK_QUERY only — the parsing/reporting code is name-driven.

SYNTHETIC INPUT: tools/jank_report_testdata/synthetic.csv covers 3
dropped frames (one per dominant app stage: UI, RenderThread, GL) + a
SurfaceFlinger-late dropped frame + an on-time control frame + edge
cases (a UI/RenderThread tie; a frame with missing slices that falls
back to its waker column). Test:

    python3 tools/jank_report.py --selftest

Exit codes: 0 = report/selftest OK; 2 = usage or IO error; 3 = parse
error; 4 = self-test failure (see stderr).
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import sys
from collections import Counter
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# The query this script consumes.  NOTE ("sabah doğrulanacak"): emitted
# against the standard Perfetto frame-timeline tables; the exact column
# spellings must be verified once against the installed trace_processor
# in the morning (see docs/PANEL_JANK_DIAGNOSIS.md).  The column NAMES of
# the SELECT are the contract the parser matches on header row.
# ---------------------------------------------------------------------------
JANK_QUERY = """
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
"""

# The CSV header contract (names the parser looks for; order irrelevant).
EXPECTED_COLUMNS = [
    "frame_fff",
    "frame_name",
    "frame_ts",
    "actual_dur",
    "jank_type",
    "expected_dur",
    "slice_name",
    "slice_thread",
    "slice_process",
    "slice_ts",
    "slice_dur",
    "waker_type",
    "waker_thread",
    "waker_uiname",
    "expected_deadline_ns",
]

# Header spellings tolerated from trace_processor variants (lowercase key
# -> contract column).  The parser lowercases and strips whitespace/'"'.
HEADER_ALIASES: Dict[str, str] = {c: c for c in EXPECTED_COLUMNS}
for extra, canonical in [
    ("id", "frame_fff"),
    ("name", "frame_name"),
    ("ts", "frame_ts"),
    ("dur", "actual_dur"),
    ("expected_timeline_dur", "expected_dur"),
    ("uiname", "waker_uiname"),
    ("process", "slice_process"),
    ("thread_name", "slice_thread"),
]:
    HEADER_ALIASES[extra] = canonical


@dataclass
class Slice:
    """One contributing slice: a thread/process did [ts, ts+dur) of work."""

    name: str
    process: str
    thread: str
    ts: int
    dur: int

    @property
    def end(self) -> int:
        return self.ts + self.dur

    def overlaps(self, lo: int, hi: int) -> bool:
        return self.ts < hi and self.end > lo


@dataclass
class FrameRow:
    """One frame_timeline frame with its contributing slices."""

    frame_fff: int
    frame_name: str
    frame_ts: int
    actual_dur: int
    jank_type: str
    expected_dur: int
    waker_type: str
    waker_thread: str
    waker_uiname: str
    expected_deadline_ns: int
    slices: List[Slice] = field(default_factory=list)

    @property
    def dropped(self) -> bool:
        # trace_processor jank_type strings: "Dropped Frame",
        # "Dropped Frame vsync deadline", ... — substring match is the pin.
        return "dropped" in (self.jank_type or "").lower()


def _as_int(v: Optional[str]) -> int:
    v = (v or "").strip().strip('"')
    if not v:
        return 0
    try:
        return int(float(v))
    except ValueError:
        return 0


def _as_str(v: Optional[str]) -> str:
    return (v or "").strip().strip('"')


def _build_frame(
    raw: Dict[str, str], defaults: Dict[str, str]
) -> Tuple[FrameRow, Optional[Slice]]:
    """One CSV row -> (frame fields, slice-if-any).  Requires frame_fff."""
    fff = _as_int(raw.get("frame_fff", ""))
    frame = FrameRow(
        frame_fff=fff,
        frame_name=_as_str(raw.get("frame_name", defaults.get("frame_name", ""))),
        frame_ts=_as_int(raw.get("frame_ts", defaults.get("frame_ts", ""))),
        actual_dur=_as_int(raw.get("actual_dur", defaults.get("actual_dur", ""))),
        jank_type=_as_str(raw.get("jank_type", defaults.get("jank_type", ""))),
        expected_dur=_as_int(raw.get("expected_dur", defaults.get("expected_dur", ""))),
        waker_type=_as_str(raw.get("waker_type", defaults.get("waker_type", ""))),
        waker_thread=_as_str(raw.get("waker_thread", defaults.get("waker_thread", ""))),
        waker_uiname=_as_str(raw.get("waker_uiname", defaults.get("waker_uiname", ""))),
        expected_deadline_ns=_as_int(
            raw.get("expected_deadline_ns", defaults.get("expected_deadline_ns", ""))
        ),
    )
    slice_dur = _as_int(raw.get("slice_dur", ""))
    slice_ts = _as_int(raw.get("slice_ts", ""))
    s: Optional[Slice] = None
    if slice_dur > 0 or slice_ts > 0:
        s = Slice(
            name=_as_str(raw.get("slice_name", "")),
            process=_as_str(raw.get("slice_process", "")),
            thread=_as_str(raw.get("slice_thread", "")),
            ts=slice_ts,
            dur=slice_dur,
        )
    return frame, s


def _map_header(cells: Sequence[str]) -> Optional[Dict[int, str]]:
    """CSV header cells -> {column-index: canonical contract name}; None if a
    bare data row (no recognizable header)."""
    normalized = [c.strip().strip('"').lower() for c in cells]
    mapped: Dict[int, str] = {}
    hits = 0
    for idx, cell in enumerate(normalized):
        canon = HEADER_ALIASES.get(cell)
        if canon is not None and canon not in mapped.values():
            mapped[idx] = canon
            hits += 1
    # A real trace_processor header names the frame columns; anything with
    # fewer than two known names is treated as a data row (not a header).
    if hits >= 2 and "frame_fff" in mapped.values():
        return mapped
    return None


def parse_input(text: str) -> List[FrameRow]:
    """Parse the full CSV: header first, then one row per (frame x slice)."""
    if not text or not text.strip():
        print(
            "jank_report: input is EMPTY — did trace_processor output anything?",
            file=sys.stderr,
        )
        sys.exit(3)
    reader = csv.reader(io.StringIO(text))
    frames: "Dict[int, FrameRow]" = {}
    row_map: Optional[Dict[int, str]] = None
    line_no = 0
    mapped_names = EXPECTED_COLUMNS
    for cells in reader:
        line_no += 1
        if not cells or all((c or "").strip() == "" for c in cells):
            continue  # blank/footer line: degrade gracefully
        if row_map is None:
            candidate = _map_header(cells)
            if candidate is not None:
                row_map = candidate
                mapped_names = [c for c in EXPECTED_COLUMNS if c in candidate.values()]
                continue
            # No header at all: fall back to the documented column ORDER.
            row_map = {i: c for i, c in enumerate(EXPECTED_COLUMNS)}
            print(
                "jank_report: no recognizable header — assuming the documented "
                "column order (see EXPECTED_COLUMNS in the script)",
                file=sys.stderr,
            )
            if len(cells) < len(EXPECTED_COLUMNS):
                print(
                    f"jank_report: line {line_no}: {len(cells)} columns < "
                    f"{len(EXPECTED_COLUMNS)} expected — regenerate the CSV with "
                    "JANK_QUERY from this script",
                    file=sys.stderr,
                )
                sys.exit(3)
        if len(cells) < len(row_map):
            print(
                f"jank_report: line {line_no}: {len(cells)} columns but the "
                f"header promises {len(row_map)} — corrupt CSV?",
                file=sys.stderr,
            )
            sys.exit(3)
        raw = {row_map[i]: cells[i] for i in row_map if i < len(cells)}
        if "frame_fff" not in raw or not _as_int(raw.get("frame_fff", "")):
            continue  # not a frame row (trace_processor status line etc.)
        frame, sl = _build_frame(raw, {})
        prev = frames.get(frame.frame_fff)
        if prev is None:
            frames[frame.frame_fff] = frame
            prev = frame
        else:
            # Later rows of the same frame only refine waker/jank data.
            if frame.jank_type and not prev.jank_type:
                prev.jank_type = frame.jank_type
            if frame.waker_thread and not prev.waker_thread:
                prev.waker_thread = frame.waker_thread
            if frame.waker_type and not prev.waker_type:
                prev.waker_type = frame.waker_type
        if sl is not None:
            prev.slices.append(sl)
    if not frames:
        print(
            "jank_report: no frames parsed — is this the JANK_QUERY output?",
            file=sys.stderr,
        )
        sys.exit(3)
    del mapped_names
    return list(frames.values())


# ---------------------------------------------------------------------------
# Verdict logic
# ---------------------------------------------------------------------------

def stage_of(thread: str, process: str) -> str:
    """The stage bucket a slice's thread belongs to. Substring matching,
    ordered most-specific first; 'OTHER' for anything unrecognized."""
    t = (thread or "").lower()
    p = (process or "").lower()
    if "renderthread" in t or "hwui" in t:
        return "RenderThread"
    if p.startswith("surfaceflinger") or "surfaceflinger" in p:
        return "SurfaceFlinger"
    if "glthread" in t or t.endswith("gl") or "egl" in t:
        return "GL"
    if "riteye" in t or "engine" in t or "native" in t or "render" in t or "preview" in t:
        # the app's own native/engine worker threads (the GLES window
        # present rides these; ClipEditorScreen names them via the JNI
        # preview engine thread)
        return "GL"
    if "ritim" in p or "ritimcraw" in p or p.startswith("com.ritim"):
        if "main" in t or t == "" or "ui" in t:
            return "UI"
        return "GL"
    if "binder" in t:
        return "SurfaceFlinger"
    return "OTHER"


def verdict_for(frame: FrameRow) -> Tuple[str, str]:
    """The (verdict, timing_hint) for one DROPPED frame."""
    if frame.expected_dur <= 0:
        # Degrade: no expected timeline slice survived the join — fall
        # back to the actual dur as the window (frame-clocked verdict).
        if frame.actual_dur <= 0:
            return ("UNKNOWN", "no expected and no actual duration")
        window = frame.actual_dur
        note = "expected_dur missing; actual_dur used as the window"
    else:
        window = frame.expected_dur
        note = ""
    lo = frame.frame_ts
    hi = frame.frame_ts + window
    contributing: List[Tuple[str, int]] = []
    for s in frame.slices:
        if s.dur <= 0 or not s.overlaps(lo, hi):
            continue
        stage = stage_of(s.thread, s.process)
        if stage == "OTHER":
            continue
        span = min(s.end, hi) - max(s.ts, lo)
        contributing.append((stage, span))
    if not contributing:
        waker = frame.waker_thread or frame.waker_uiname or frame.waker_type
        if waker:
            stage = stage_of(waker, frame.waker_uiname)
            if stage == "OTHER":
                stage = waker  # name it honestly, whatever it is
            return (
                f"stage={stage.upper()}",
                f"waker='{waker}' (no slices overlapped the window) {note}".strip(),
            )
        return ("UNKNOWN", f"no slices, no waker {note}".strip())
    contributing.sort(key=lambda x: (-x[1], x[0]))
    top = contributing[0][1]
    tied = sorted({stage for stage, span in contributing if span == top})
    if len(tied) > 1:
        return (
            "TIE(" + "|".join(tied) + ")",
            f"top_span_ns={top} over {len(contributing)} slices {note}".strip(),
        )
    hint = (
        f"winner_span_ns={top} over {len(contributing)} slices, "
        f"actual_dur={frame.actual_dur} expected_dur={frame.expected_dur} {note}"
    ).strip()
    return (tied[0], hint)


def report(frames: List[FrameRow]) -> Tuple[str, str]:
    """Build (table_text, summary_line) for the verdict report."""
    dropped = [f for f in frames if f.dropped]
    ontime = [f for f in frames if not f.dropped]
    lines: List[str] = []
    lines.append("PER-FRAME VERDICT TABLE (dropped frames only)")
    lines.append("-" * 100)
    lines.append(
        f"{'frame':>8} {'ts_ns':>12} {'jank_type':<24} {'verdict':<22} timing_hint"
    )
    lines.append("-" * 100)
    histogram: Counter = Counter()
    stages = ("UI", "RenderThread", "GL", "SurfaceFlinger")
    for f in sorted(dropped, key=lambda x: x.frame_ts):
        v, hint = verdict_for(f)
        if v.startswith("TIE("):
            inner = v[v.index("(") + 1 : v.index(")")]
            parts = inner.split("|")
            for part in parts:
                histogram[part] += 1.0 / len(parts)
        elif v.startswith("stage="):
            # waker fallback: count it toward the named stage when it is
            # a known one (SURFACEFLINGER -> SurfaceFlinger), else its
            # own honest bar.
            named = v[len("stage="):].lower()
            known = next((s for s in stages if s.lower() == named), None)
            histogram[known if known else v] += 1
        else:
            histogram[v] += 1
        label = (v[:22] + "..") if len(v) > 22 else v
        lines.append(
            f"{f.frame_fff:>8} {f.frame_ts:>12} "
            f"{((f.jank_type or '?')[:24]):<24} {label:<22} {hint}"
        )
    lines.append("-" * 100)
    if not dropped:
        lines.append("(no dropped frames in this trace window)")
    lines.append("")
    lines.append("GUILTY-THREAD HISTOGRAM (dropped frames; ties split evenly)")
    for stage in stages:
        count = histogram[stage]
        if count:
            lines.append(f"  {stage:<14} {count:g}")
    for k in sorted(k for k in histogram if k not in stages):
        lines.append(f"  {k:<14} {histogram[k]:g}")
    total = len(frames)
    lines.append("")
    lines.append(
        f"SUMMARY: dropped={len(dropped)} ontime={len(ontime)} "
        f"(dropped/total = {len(dropped)}/{total})"
    )
    summary = f"dropped={len(dropped)} ontime={len(ontime)}"
    return ("\n".join(lines) + "\n", summary)


def selftest() -> None:
    """Load the pinned synthetic input and pin the expected verdicts."""
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "jank_report_testdata", "synthetic.csv")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError as e:
        print(f"jank_report: selftest cannot read {path}: {e}", file=sys.stderr)
        sys.exit(4)
    frames = parse_input(text)
    dropped = {f.frame_fff: f for f in frames if f.dropped}
    expect = {
        1010: "UI",
        1020: "RenderThread",
        1030: "GL",
        1040: "SurfaceFlinger",
        1050: "TIE(RenderThread|UI)",
        1060: "stage=SURFACEFLINGER",  # waker fallback (frame with missing slices)
    }
    failures: List[str] = []
    for key, want in expect.items():
        f = dropped.get(key)
        if f is None:
            failures.append(f"frame {key} is not reported as dropped")
            continue
        v, _ = verdict_for(f)
        if want not in v:
            failures.append(f"frame {key}: verdict '{v}' does not contain '{want}'")
    control = [f for f in frames if f.frame_fff == 1070]
    if len(control) != 1 or control[0].dropped:
        failures.append("control frame 1070 must parse as NOT dropped")
    if len(frames) < 7:
        failures.append(f"expected >=7 frames, parsed {len(frames)}")
    table, _ = report(frames)
    sys.stdout.write(table)
    if failures:
        for m in failures:
            print(f"SELFTEST FAIL: {m}", file=sys.stderr)
        sys.exit(4)
    print(
        "SELFTEST OK: 3 app-stage drops (UI/RenderThread/GL) + SF-late drop + "
        "tie + waker fallback + on-time control"
    )


def main(argv: Optional[List[str]] = None) -> None:
    p = argparse.ArgumentParser(
        prog="jank_report.py",
        description=(
            "Perfetto frame-timeline jank verdict table. Generate the input "
            "CSV with the JANK_QUERY constant inside this script (see "
            "docs/PANEL_JANK_DIAGNOSIS.md), then: jank_report.py --input out.csv"
        ),
    )
    p.add_argument(
        "--input", "-i", help="trace_processor CSV produced with JANK_QUERY"
    )
    p.add_argument(
        "--selftest", action="store_true",
        help="run the built-in verdict test against synthetic.csv",
    )
    p.add_argument(
        "--print-query", action="store_true",
        help="print the SQL query this script expects, then exit",
    )
    args = p.parse_args(argv)
    if args.selftest:
        selftest()
        return
    if args.print_query:
        sys.stdout.write(JANK_QUERY)
        return
    if not args.input:
        p.print_usage(sys.stderr)
        print(
            "jank_report: --input is required (or --selftest / --print-query)",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        with open(args.input, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError as e:
        print(f"jank_report: cannot read {args.input}: {e}", file=sys.stderr)
        sys.exit(2)
    frames = parse_input(text)
    table, _ = report(frames)
    sys.stdout.write(table)


if __name__ == "__main__":
    main()
