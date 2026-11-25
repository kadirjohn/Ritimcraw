# visual-progress

Static, no-build timeline of the Ritimcraw change history (done, in progress, planned, blocked).
No npm, no CDN, no network requests.

## Preview

    python3 -m http.server -d STEPS/visual-progress     # then open http://localhost:8000
    # or just open STEPS/visual-progress/index.html (file:// uses data/steps.js)

Over http the page fetches `data/steps.json`; on file:// it falls back to `window.STEPS_DATA` from `data/steps.js`.

## Layout

    index.html  css/style.css  js/app.js
    data/steps/<id>.json     one file per step (source of truth, edit these)
    data/steps/_template.json  copy this for a new step (files starting with _ are ignored)
    data/steps.json, data/steps.js   GENERATED, do not edit by hand
    assets/                  optional screenshots
    tools/build-data.py      validate + regenerate (Python 3 stdlib only)

## Schema (data/steps/<id>.json)

| field | meaning |
|---|---|
| `id` | must equal the file name without `.json`. Backfilled: `NNN-<hash>`; new ones: e.g. `p09-short-name` |
| `title` | short lowercase imperative title (merge subject without `merge: `) |
| `date` | `YYYY-MM-DD` or `TBD` |
| `status` | `done`, `in-progress`, `planned`, `blocked` |
| `commit` | `{hash, date, merge_subject}` for `done` steps only (from `git log`), otherwise `null` |
| `tests` | command plus its real result, or `not recorded` / `not run`. Never invent it |
| `description` | short text |
| `added`, `removed`, `changed` | lists of strings |
| `files` | `[{path, change}]`, change is `A`, `M`, `D` (or `?`) |
| `sources` | `[{name, url?, note?}]`, only what was actually used (Klipp, darktable, ...) |
| `screenshot` | optional path under `assets/`, e.g. `assets/041-abc1234.png` |

A step is `done` only when tests passed AND the commit/merge exists.

## Add or update a step

    cp STEPS/visual-progress/data/steps/_template.json STEPS/visual-progress/data/steps/p09-my-step.json
    # edit it; hash/date from: git log -1 --format='%h %cs' <merge-commit>
    python3 STEPS/visual-progress/tools/build-data.py

The script validates every step and rewrites `data/steps.json` and `data/steps.js`; on any error it writes nothing.
Backfilled history came from `git log --merges --first-parent main`; when a planned step is finished, edit its JSON
(status `done`, commit, tests) instead of adding a duplicate.

## Standing rule (soft, "from time to time to keep the project visible")

After an agent's work is finished, successful, tests passed and the commit/merge exists, it appends or updates its
step here (per-step JSON, then run `tools/build-data.py`), with the commit hash and date. Screenshots are optional:
only agents that can actually view an image add one (copy to `assets/`, set `screenshot`); others skip it. Never add
placeholder images.

## Git note

`STEPS/` is gitignored as a whole; `.gitignore` carries an exception so only `STEPS/visual-progress/` is tracked. Some checkouts also list `STEPS` in `.git/info/exclude`; there, add new step files with
`git add -f STEPS/visual-progress/...` (already-tracked files need nothing).
