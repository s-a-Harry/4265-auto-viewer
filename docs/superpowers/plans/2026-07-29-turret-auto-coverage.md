# Turret Auto Coverage Page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `viewer/coverage.html`, a five-tab page that shows which of 4265's existing PathPlanner routes survive the turret rebuild, what the new 3-auto set still needs, and how 4265's auto scoring compares to the 30 WVRoX teams.

**Architecture:** Two small Python generators read the robot repo and the scouting repo and emit static JSON into `viewer/`. The page is a single self-contained HTML file that fetches that JSON plus the existing `autos.json`, and draws on the same field image with the same coordinate transform the other pages use. No Java, no trajectory generation, no physics — the page plays back and classifies data that already exists.

**Tech Stack:** Python 3.12 + pytest 9.1.1 for the generators; vanilla HTML/CSS/JS with Canvas 2D for the page; headless Chrome for DOM smoke tests. No frameworks, no build step, no external dependencies at runtime.

**Spec:** `docs/superpowers/specs/2026-07-29-turret-auto-rebuild-design.md`

## Global Constraints

- **Branch:** work on `feat/turret-auto-coverage` (already created, spec committed at `96ed927`).
- **Never write to `2026-Robot`.** This repo reads that one. Default location `../2026-Robot`, same convention as `regen.sh`.
- **Auto period is 20 s** (2026 REBUILT), not 15. Constant name `AUTO_PERIOD`, matching `single.html:221`.
- **Field size is 16.54 × 8.07 m.** Field image mapping constants must match `single.html:218-220` exactly: `IMG_W = 3508, IMG_H = 1814, PPM = 200, MARGIN = 0.5, RENDER_W = 1754`.
- **Palette:** reuse the Material 3 dark variables from `single.html:11-23` verbatim (`--surface`, `--surface-1`, `--surface-2`, `--outline`, `--on-surface`, `--on-surface-dim`, `--primary`, `--secondary`, `--warn-bg`, `--warn-fg`, `--err`). Do not invent new colours; PathPlanner familiarity is deliberate.
- **Salvage class colours:** green `#4ade80`, yellow `--warn-fg` (`#e3c08a`), grey `--outline` (`#49454f`).
- **Phones are a supported target,** not a bonus. Every tab must be usable at 390 px wide.
- **Measured ground truth** — any change to these numbers means a regression: 85 path files = **67 used + 18 orphans**; 67 used = **36 green + 31 yellow**; 41 autos; 2 null-path autos; 2 duplicate pairs; 2 autos over 20 s.
- **The `null` path reference is not a path file.** It must be excluded before computing counts. This is the exact bug that made the first hand-count overshoot by one.
- **Do not restructure `single.html` or `alliance.html`.** They work and the event is in two days. `coverage.html` inlines its own copy of the ~40 lines of field transform, following the established one-file-per-page pattern. This duplication is accepted deliberately.

---

### Task 1: Path salvage generator

Reads the robot repo's `.path` and `.auto` files, classifies every path green/yellow/grey, finds the defects, and writes `viewer/paths.json`.

**Files:**
- Create: `tools/build_paths.py`
- Create: `tests/test_build_paths.py`
- Create: `tests/conftest.py`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `viewer/paths.json` and these importable functions —
  - `classify(path_obj: dict) -> str` returns `"green"` or `"yellow"`
  - `walk_paths(command: dict | None) -> list[str | None]` returns ordered path names, `None` preserved
  - `build(robot_dir: Path) -> dict` returns the full payload
  - Payload shape depended on by Tasks 2–5:
    ```json
    {
      "generatedAt": "2026-07-29T00:00:00Z",
      "counts": {"files": 85, "used": 67, "orphans": 18, "green": 36, "yellow": 31},
      "paths": [{"name": "R Trench", "cls": "green", "rotationTargets": 0,
                 "waypoints": 4, "startRot": 0.0, "endRot": 0.0,
                 "usedBy": ["R Trench 2 Dips", "R Trench Outpost"]}],
      "autos": [{"name": "S R Back to Middle NC", "folder": "No Climb",
                 "pathNames": ["R Trench", "R Trench Reverse", null, "R Trench Back to Mid"],
                 "defects": ["null-path"]}],
      "duplicates": [["Back up Simple Right + Outpost", "Simple Right + Outpost"],
                     ["New Auto", "Simple L NC"]]
    }
    ```
  - `defects` values are exactly: `"null-path"`, `"duplicate"`, `"junk"`. Over-20 s is **not** a defect here — it comes from `autos.json` durations in the page, because this generator never computes timing.
  - `JUNK_NAMES` is a module-level `frozenset` of the six known scratch autos.

- [ ] **Step 1: Write the failing tests**

Create `tests/conftest.py`:

```python
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

REPO = Path(__file__).resolve().parents[1]
ROBOT = REPO.parent / "2026-Robot"
PPLAN = ROBOT / "src" / "main" / "deploy" / "pathplanner"
```

Create `tests/test_build_paths.py`:

```python
import json
import pytest
from conftest import PPLAN, ROBOT
from tools.build_paths import classify, walk_paths, build, JUNK_NAMES

pytestmark = pytest.mark.skipif(
    not PPLAN.exists(), reason=f"robot repo not at {ROBOT}"
)


def test_classify_no_rotation_targets_is_green():
    assert classify({"rotationTargets": []}) == "green"
    assert classify({}) == "green"


def test_classify_with_rotation_targets_is_yellow():
    assert classify({"rotationTargets": [{"rotationDegrees": 90}]}) == "yellow"


def test_walk_paths_preserves_order():
    cmd = {"type": "sequential", "data": {"commands": [
        {"type": "path", "data": {"pathName": "A"}},
        {"type": "path", "data": {"pathName": "B"}},
    ]}}
    assert walk_paths(cmd) == ["A", "B"]


def test_walk_paths_recurses_into_nested_groups():
    cmd = {"type": "sequential", "data": {"commands": [
        {"type": "path", "data": {"pathName": "A"}},
        {"type": "parallel", "data": {"commands": [
            {"type": "path", "data": {"pathName": "B"}},
        ]}},
    ]}}
    assert walk_paths(cmd) == ["A", "B"]


def test_walk_paths_preserves_null_pathname():
    """A null pathName is a real defect in the repo, not something to drop."""
    cmd = {"type": "sequential", "data": {"commands": [
        {"type": "path", "data": {"pathName": "A"}},
        {"type": "path", "data": {}},
    ]}}
    assert walk_paths(cmd) == ["A", None]


def test_counts_reconcile_exactly():
    """green + yellow + orphans must equal total files.

    Regression guard: counting the null pathName as a used path made this
    overshoot by one during design.
    """
    c = build(ROBOT)["counts"]
    assert c["green"] + c["yellow"] == c["used"]
    assert c["used"] + c["orphans"] == c["files"]


def test_measured_ground_truth():
    c = build(ROBOT)["counts"]
    assert c == {"files": 85, "used": 67, "orphans": 18, "green": 36, "yellow": 31}


def test_finds_both_null_path_autos():
    autos = build(ROBOT)["autos"]
    broken = sorted(a["name"] for a in autos if "null-path" in a["defects"])
    assert broken == ["S R Back to Middle NC", "Shoot 8 R Trench 2 Dips"]


def test_finds_both_duplicate_pairs():
    dups = build(ROBOT)["duplicates"]
    assert sorted(dups) == [
        ["Back up Simple Right + Outpost", "Simple Right + Outpost"],
        ["New Auto", "Simple L NC"],
    ]


def test_duplicate_autos_are_flagged_on_both_members():
    autos = {a["name"]: a for a in build(ROBOT)["autos"]}
    assert "duplicate" in autos["New Auto"]["defects"]
    assert "duplicate" in autos["Simple L NC"]["defects"]


def test_junk_autos_flagged():
    autos = {a["name"]: a for a in build(ROBOT)["autos"]}
    for name in JUNK_NAMES:
        assert "junk" in autos[name]["defects"], name


def test_all_41_autos_present():
    assert len(build(ROBOT)["autos"]) == 41


def test_orphan_paths_are_used_by_nobody():
    p = build(ROBOT)["paths"]
    orphans = [x for x in p if x["cls"] == "grey"]
    assert len(orphans) == 18
    assert all(x["usedBy"] == [] for x in orphans)


def test_r_trench_is_the_most_shared_route():
    p = {x["name"]: x for x in build(ROBOT)["paths"]}
    assert len(p["R Trench"]["usedBy"]) == 11
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python -m pytest tests/test_build_paths.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'tools.build_paths'`

- [ ] **Step 3: Write the implementation**

Create `tools/__init__.py` as an empty file, then `tools/build_paths.py`:

```python
#!/usr/bin/env python3
"""Classify 2026-Robot's PathPlanner routes for the turret rebuild.

A turret decouples heading from scoring, so route geometry survives the
rebuild but baked-in rotation targets do not. This sorts every .path file
into green (reuse as-is), yellow (route fine, strip the rotation targets)
and grey (referenced by no auto, delete), and flags defects in the .auto
files that would bite at an event.

    python tools/build_paths.py [path-to-2026-Robot]
"""
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

JUNK_NAMES = frozenset({
    "Awesome", "Not Awesome", "EventTest", "SysCheck", "New Auto", "New New Auto",
})


def classify(path_obj):
    """green if the route carries no heading commitment, else yellow."""
    return "yellow" if path_obj.get("rotationTargets") else "green"


def walk_paths(command, out=None):
    """Ordered path names driven by a PathPlanner command tree.

    None is preserved: a null pathName is a real defect worth reporting,
    not noise to drop.
    """
    if out is None:
        out = []
    if not isinstance(command, dict):
        return out
    if command.get("type") == "path":
        out.append((command.get("data") or {}).get("pathName"))
    for child in (command.get("data") or {}).get("commands") or []:
        walk_paths(child, out)
    return out


def _load(p):
    return json.loads(p.read_text(encoding="utf-8"))


def build(robot_dir):
    robot_dir = Path(robot_dir)
    root = robot_dir / "src" / "main" / "deploy" / "pathplanner"
    path_files = sorted((root / "paths").glob("*.path"))
    auto_files = sorted((root / "autos").glob("*.auto"))

    autos, by_tree = [], {}
    for f in auto_files:
        doc = _load(f)
        name = f.stem
        names = walk_paths(doc.get("command"))
        autos.append({
            "name": name,
            "folder": doc.get("folder") or "",
            "pathNames": names,
            "defects": [],
        })
        key = hashlib.md5(
            json.dumps(doc.get("command"), sort_keys=True).encode()
        ).hexdigest()
        by_tree.setdefault(key, []).append(name)

    duplicates = sorted(sorted(v) for v in by_tree.values() if len(v) > 1)
    dup_members = {n for pair in duplicates for n in pair}

    for a in autos:
        if any(n is None for n in a["pathNames"]):
            a["defects"].append("null-path")
        if a["name"] in dup_members:
            a["defects"].append("duplicate")
        if a["name"] in JUNK_NAMES:
            a["defects"].append("junk")

    # A null pathName is not a file. Excluding it here is what makes
    # used + orphans == files hold.
    used = {n for a in autos for n in a["pathNames"] if n is not None}

    used_by = {}
    for a in autos:
        for n in a["pathNames"]:
            if n is not None:
                used_by.setdefault(n, [])
                if a["name"] not in used_by[n]:
                    used_by[n].append(a["name"])

    paths = []
    for f in path_files:
        doc = _load(f)
        name = f.stem
        is_used = name in used
        paths.append({
            "name": name,
            "cls": classify(doc) if is_used else "grey",
            "rotationTargets": len(doc.get("rotationTargets") or []),
            "waypoints": len(doc.get("waypoints") or []),
            "startRot": (doc.get("idealStartingState") or {}).get("rotation"),
            "endRot": (doc.get("goalEndState") or {}).get("rotation"),
            "usedBy": sorted(used_by.get(name, [])),
        })

    on_disk = {p["name"] for p in paths}
    counts = {
        "files": len(paths),
        "used": len(used & on_disk),
        "orphans": sum(1 for p in paths if p["cls"] == "grey"),
        "green": sum(1 for p in paths if p["cls"] == "green"),
        "yellow": sum(1 for p in paths if p["cls"] == "yellow"),
    }

    missing = sorted(used - on_disk)
    return {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "counts": counts,
        "paths": paths,
        "autos": autos,
        "duplicates": duplicates,
        "missingPaths": missing,
    }


def main():
    robot = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1].parent / "2026-Robot"
    payload = build(robot)
    out = Path(__file__).resolve().parents[1] / "viewer" / "paths.json"
    out.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    c = payload["counts"]
    print(f"{out}: {c['files']} files = {c['used']} used + {c['orphans']} orphans")
    print(f"  green {c['green']}  yellow {c['yellow']}")
    if payload["missingPaths"]:
        print(f"  !! referenced but missing: {payload['missingPaths']}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python -m pytest tests/test_build_paths.py -v`
Expected: PASS, 13 passed.

- [ ] **Step 5: Generate the data file**

Run: `python tools/build_paths.py`
Expected output:
```
.../viewer/paths.json: 85 files = 67 used + 18 orphans
  green 36  yellow 31
```

- [ ] **Step 6: Commit**

```bash
git add tools/ tests/ viewer/paths.json
git commit -m "Add path salvage classifier for the turret rebuild"
```

---

### Task 2: Page shell and Map tab

The five-tab frame plus Tab 1: every route on one field, coloured by salvage class.

**Files:**
- Create: `viewer/coverage.html`
- Create: `tests/test_page_smoke.py`

**Interfaces:**
- Consumes: `viewer/paths.json` from Task 1; the existing `viewer/autos.json` (fields `name`, `folder`, `paths` — an ordered list of path names — `drivingTimeSec`, `eventMarkers`, `commands`, `samples[{t,x,y,deg,v}]`); `viewer/source.json`; `viewer/field26.png`.
- Produces, relied on by Tasks 3–7:
  - `showTab(id)` where id is one of `map`, `tree`, `gap`, `audit`, `field`
  - `isolate(autoName)` — draws one auto's route alone on the Map and switches to it
  - `lane(y)` → `"left" | "centre" | "right"`
  - Globals `AUTOS` (parsed `autos.json`), `PATHS` (parsed `paths.json`), `PATH_BY_NAME` (Map name→path record), `AUTO_BY_NAME` (Map name→auto record from `autos.json`)
  - `<div id="tab-map">`, `#tab-tree`, `#tab-gap`, `#tab-audit`, `#tab-field` — later tasks fill their own div and touch no other.

- [ ] **Step 1: Write the failing smoke test**

Create `tests/test_page_smoke.py`:

```python
"""DOM smoke tests: serve viewer/ and let headless Chrome render the page.

file:// will not work — fetch() of the JSON is blocked by CORS there.
"""
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest
from conftest import REPO

CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
VIEWER = REPO / "viewer"


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def server():
    port = _free_port()
    proc = subprocess.Popen(
        [sys.executable, "-m", "http.server", str(port),
         "--directory", str(VIEWER), "--bind", "127.0.0.1"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(50):
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                break
        except OSError:
            time.sleep(0.1)
    else:
        proc.kill()
        pytest.fail("http.server did not come up")
    yield f"http://127.0.0.1:{port}"
    proc.terminate()


@pytest.fixture(scope="session")
def dom(server, tmp_path_factory):
    """Rendered DOM of coverage.html after its fetches settle."""
    if not CHROME.exists():
        pytest.skip("Chrome not installed at the expected path")
    profile = tmp_path_factory.mktemp("chrome")
    out = subprocess.run(
        [str(CHROME), "--headless", "--disable-gpu", "--no-sandbox",
         f"--user-data-dir={profile}", "--virtual-time-budget=6000",
         "--dump-dom", f"{server}/coverage.html"],
        capture_output=True, text=True, timeout=90,
    )
    return out.stdout


def test_page_renders_all_five_tabs(dom):
    for tab in ("tab-map", "tab-tree", "tab-gap", "tab-audit", "tab-field"):
        assert f'id="{tab}"' in dom, tab


def test_map_reports_the_measured_counts(dom):
    text = re.sub(r"<[^>]+>", " ", dom)
    assert "36" in text and "31" in text and "18" in text


def test_no_uncaught_error_banner(dom):
    assert 'id="fatal" hidden' in dom or 'id="fatal"' not in dom.split("<body")[0]
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest tests/test_page_smoke.py -v`
Expected: FAIL — Chrome returns a 404 page, so `id="tab-map"` is absent.

- [ ] **Step 3: Write `viewer/coverage.html`**

Create the file with this structure. The palette block is copied verbatim from `single.html:11-23`; the field transform from `single.html:218-220`.

```html
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#1c1b1f">
<title>4265 Auto Coverage — turret rebuild</title>
<style>
  :root {
    --surface: #1c1b1f; --surface-1: #232227; --surface-2: #2b2930;
    --outline: #49454f; --on-surface: #e6e1e5; --on-surface-dim: #cac4d0;
    --primary: #d0bcff; --secondary: #ccc2dc;
    --warn-bg: #2b2416; --warn-fg: #e3c08a; --err: #f2b8b5;
    --green: #4ade80;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { height: 100%; }
  body {
    margin: 0; background: var(--surface); color: var(--on-surface);
    font: 14px/1.5 ui-sans-serif, system-ui, Roboto, "Segoe UI", sans-serif;
    display: flex; flex-direction: column; height: 100dvh; overflow: hidden;
  }
  header { padding: 10px 16px 0; border-bottom: 1px solid var(--outline); }
  header h1 { margin: 0; font-size: 15px; font-weight: 600; }
  header .src { color: var(--on-surface-dim); font-size: 11px; opacity: .7; }
  nav { display: flex; gap: 4px; margin-top: 8px; overflow-x: auto; }
  nav button {
    background: none; border: 0; border-bottom: 2px solid transparent;
    color: var(--on-surface-dim); padding: 8px 12px; font: inherit;
    font-size: 13px; white-space: nowrap; cursor: pointer;
  }
  nav button[aria-selected="true"] { color: var(--primary); border-bottom-color: var(--primary); }
  main { flex: 1; min-height: 0; overflow: hidden; }
  main > section { display: none; height: 100%; }
  main > section.on { display: flex; }
  .cls-green { color: var(--green); }
  .cls-yellow { color: var(--warn-fg); }
  .cls-grey { color: var(--on-surface-dim); opacity: .65; }
  #fatal { padding: 16px; color: var(--err); }

  /* ---- map tab ---- */
  #tab-map { flex-direction: row; }
  #map-side {
    width: 280px; flex: none; background: var(--surface-1);
    border-right: 1px solid var(--outline); overflow-y: auto; padding: 12px;
  }
  #map-stage { flex: 1; min-width: 0; display: grid; place-items: center; padding: 8px; }
  #map-canvas { max-width: 100%; max-height: 100%; }
  .legend { display: flex; flex-direction: column; gap: 6px; margin-bottom: 14px; }
  .legend label { display: flex; align-items: center; gap: 8px; font-size: 13px; cursor: pointer; }
  .count { color: var(--on-surface-dim); margin-left: auto; font-variant-numeric: tabular-nums; }
  .routelist { margin-top: 10px; }
  .routelist div {
    padding: 4px 6px; border-radius: 4px; cursor: pointer;
    font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .routelist div:hover, .routelist div.sel { background: var(--surface-2); }
  @media (max-width: 720px) {
    #tab-map { flex-direction: column; }
    #map-side { width: auto; border-right: 0; border-bottom: 1px solid var(--outline); max-height: 42%; }
  }
</style>

<header>
  <h1>4265 Auto Coverage <span class="src" id="src"></span></h1>
  <nav id="tabs">
    <button data-tab="map" aria-selected="true">Map</button>
    <button data-tab="tree" aria-selected="false">Tree</button>
    <button data-tab="gap" aria-selected="false">Gap</button>
    <button data-tab="audit" aria-selected="false">Audit</button>
    <button data-tab="field" aria-selected="false">Field</button>
  </nav>
</header>

<main>
  <section id="tab-map" class="on">
    <div id="map-side">
      <div class="legend" id="legend"></div>
      <div class="routelist" id="routelist"></div>
    </div>
    <div id="map-stage"><canvas id="map-canvas"></canvas></div>
  </section>
  <section id="tab-tree"></section>
  <section id="tab-gap"></section>
  <section id="tab-audit"></section>
  <section id="tab-field"></section>
</main>
<div id="fatal" hidden></div>

<script>
// Field image mapping — must match single.html:218-220.
const IMG_W = 3508, IMG_H = 1814, PPM = 200, MARGIN = 0.5;
const RENDER_W = 1754;
const S = RENDER_W / IMG_W;
const AUTO_PERIOD = 20;              // 2026 REBUILT
const FIELD_Y = 8.07;

let AUTOS = null, PATHS = null;
const PATH_BY_NAME = new Map(), AUTO_BY_NAME = new Map();

/** Field thirds. Left wall is high y, right wall is low y. */
function lane(y) {
  if (y < FIELD_Y / 3) return 'right';
  if (y > 2 * FIELD_Y / 3) return 'left';
  return 'centre';
}

function showTab(id) {
  for (const s of document.querySelectorAll('main > section')) s.classList.toggle('on', s.id === 'tab-' + id);
  for (const b of document.querySelectorAll('#tabs button')) b.setAttribute('aria-selected', String(b.dataset.tab === id));
  if (id === 'map') drawMap();
}
document.getElementById('tabs').addEventListener('click', e => {
  if (e.target.dataset.tab) showTab(e.target.dataset.tab);
});

const canvas = document.getElementById('map-canvas');
const ctx = canvas.getContext('2d');
const field = new Image();
const fx = x => (x + MARGIN) * PPM * S;
const fy = y => canvas.height - (y + MARGIN) * PPM * S;

const shown = { green: true, yellow: true, grey: true };
let isolated = null;

/** Samples for one auto, split per leg so legs are not joined by a false line. */
function autoSamples(name) {
  const a = AUTO_BY_NAME.get(name);
  return a && a.samples && a.samples.length ? a.samples : null;
}

/** Which salvage class an auto's route should be drawn as: worst of its paths. */
function autoClass(name) {
  const rec = PATHS.autos.find(x => x.name === name);
  if (!rec) return 'grey';
  let cls = 'green';
  for (const p of rec.pathNames) {
    const pr = p === null ? null : PATH_BY_NAME.get(p);
    if (!pr) return 'grey';
    if (pr.cls === 'yellow') cls = 'yellow';
  }
  return cls;
}

function drawMap() {
  if (!AUTOS || !field.complete) return;
  canvas.width = RENDER_W;
  canvas.height = Math.round(IMG_H * S);
  ctx.drawImage(field, 0, 0, canvas.width, canvas.height);

  const list = isolated ? [isolated] : AUTOS.autos.map(a => a.name);
  for (const name of list) {
    const cls = autoClass(name);
    if (!isolated && !shown[cls]) continue;
    const s = autoSamples(name);
    if (!s) continue;
    ctx.strokeStyle = cls === 'green' ? '#4ade80' : cls === 'yellow' ? '#e3c08a' : '#49454f';
    ctx.globalAlpha = isolated ? 1 : 0.42;
    ctx.lineWidth = isolated ? 5 : 2.5;
    ctx.lineJoin = ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(fx(s[0].x), fy(s[0].y));
    for (let i = 1; i < s.length; i++) ctx.lineTo(fx(s[i].x), fy(s[i].y));
    ctx.stroke();
    // start marker
    ctx.globalAlpha = 1;
    ctx.fillStyle = ctx.strokeStyle;
    ctx.beginPath();
    ctx.arc(fx(s[0].x), fy(s[0].y), isolated ? 11 : 6, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalAlpha = 1;
}

function isolate(autoName) {
  isolated = isolated === autoName ? null : autoName;
  for (const d of document.querySelectorAll('#routelist div'))
    d.classList.toggle('sel', d.dataset.name === isolated);
  showTab('map');
  drawMap();
}

function buildLegend() {
  const c = PATHS.counts;
  const rows = [
    ['green', 'Green — reuse as-is', c.green],
    ['yellow', 'Yellow — strip rotation targets', c.yellow],
    ['grey', 'Grey — orphaned, delete', c.orphans],
  ];
  document.getElementById('legend').innerHTML = rows.map(([k, label, n]) => `
    <label><input type="checkbox" data-cls="${k}" checked>
      <span class="cls-${k}">${label}</span><span class="count">${n}</span></label>`).join('');
  document.getElementById('legend').addEventListener('change', e => {
    shown[e.target.dataset.cls] = e.target.checked;
    drawMap();
  });
}

function buildRouteList() {
  const el = document.getElementById('routelist');
  const names = AUTOS.autos.map(a => a.name).sort((a, b) => a.localeCompare(b));
  el.innerHTML = names.map(n => {
    const cls = autoClass(n);
    const a = AUTO_BY_NAME.get(n);
    const t = a ? a.drivingTimeSec.toFixed(1) : '?';
    const over = a && a.drivingTimeSec > AUTO_PERIOD ? ' ⚠' : '';
    return `<div data-name="${n}" class="cls-${cls}">${n} <span class="count">${t}s${over}</span></div>`;
  }).join('');
  el.addEventListener('click', e => {
    const d = e.target.closest('div[data-name]');
    if (d) isolate(d.dataset.name);
  });
}

function fatal(msg) {
  const f = document.getElementById('fatal');
  f.hidden = false;
  f.textContent = msg;
}

Promise.all([
  fetch('autos.json').then(r => r.json()),
  fetch('paths.json').then(r => r.json()),
  fetch('source.json').then(r => r.json()).catch(() => null),
]).then(([autos, paths, src]) => {
  AUTOS = autos; PATHS = paths;
  for (const p of paths.paths) PATH_BY_NAME.set(p.name, p);
  for (const a of autos.autos) AUTO_BY_NAME.set(a.name, a);
  if (src) document.getElementById('src').textContent =
    `${src.repo}@${src.branch} ${src.sha} (${src.commitDate})`;
  buildLegend();
  buildRouteList();
  field.onload = drawMap;
  field.src = 'field26.png';
  if (field.complete) drawMap();
}).catch(e => fatal('Failed to load data: ' + e.message));
</script>
```

- [ ] **Step 4: Run the smoke test to verify it passes**

Run: `python -m pytest tests/test_page_smoke.py -v`
Expected: PASS, 3 passed.

- [ ] **Step 5: Look at it**

Run: `java Serve.java 8265 viewer` and open `http://localhost:8265/coverage.html`.
Confirm by eye: the field image draws, routes are green/yellow, unchecking a legend box removes those routes, clicking a route in the list isolates it.

- [ ] **Step 6: Commit**

```bash
git add viewer/coverage.html tests/test_page_smoke.py
git commit -m "Add coverage page shell and salvage map"
```

---

### Task 3: Tree tab

Opening-path branching — the structure the drive team actually reasons about.

**Files:**
- Modify: `viewer/coverage.html` (fill `#tab-tree`, add its CSS and `buildTree()`)
- Modify: `tests/test_page_smoke.py` (append tests)

**Interfaces:**
- Consumes: `PATHS`, `AUTOS`, `AUTO_BY_NAME`, `isolate()`, `lane()` from Task 2.
- Produces: `buildTree()`, called once after data loads.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_page_smoke.py`:

```python
def test_tree_roots_at_opening_paths(dom):
    assert "R Trench" in dom and "L Trench" in dom


def test_tree_shows_root_auto_counts(dom):
    text = re.sub(r"<[^>]+>", " ", dom)
    text = re.sub(r"\s+", " ", text)
    assert "R Trench 8 autos" in text or "R Trench (8" in text


def test_tree_flags_broken_autos(dom):
    assert "S R Back to Middle NC" in dom
    assert "✖" in dom


def test_tree_flags_overruns(dom):
    assert "45 Defensive" in dom and "⚠" in dom
```

- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest tests/test_page_smoke.py -v -k tree`
Expected: FAIL — `#tab-tree` is empty.

- [ ] **Step 3: Add the CSS**

Insert before the closing `</style>`:

```css
  /* ---- tree tab ---- */
  #tab-tree { flex-direction: column; overflow-y: auto; padding: 12px 16px; }
  .root { margin-bottom: 18px; }
  .root > h3 {
    margin: 0 0 6px; font-size: 14px; font-weight: 600;
    display: flex; align-items: baseline; gap: 8px;
  }
  .root > h3 .meta { font-size: 12px; color: var(--on-surface-dim); font-weight: 400; }
  .leaf {
    display: flex; align-items: baseline; gap: 8px; padding: 3px 8px;
    border-left: 2px solid var(--outline); margin-left: 10px; cursor: pointer;
    font-size: 13px;
  }
  .leaf:hover { background: var(--surface-2); }
  .leaf .chain { color: var(--on-surface-dim); font-size: 12px; }
  .leaf .t { margin-left: auto; font-variant-numeric: tabular-nums; color: var(--on-surface-dim); }
  .leaf .t.over { color: var(--warn-fg); font-weight: 700; }
  .flags { letter-spacing: 2px; }
```

- [ ] **Step 4: Add `buildTree()`**

Insert before the `Promise.all([` block:

```javascript
const LANE_LABEL = { left: 'left wall', centre: 'centre', right: 'right wall' };

function autoFlags(name) {
  const rec = PATHS.autos.find(x => x.name === name);
  const a = AUTO_BY_NAME.get(name);
  let f = '';
  if (rec && rec.defects.includes('null-path')) f += '✖';
  if (rec && rec.defects.includes('duplicate')) f += '⧉';
  if (rec && rec.defects.includes('junk')) f += '🧪';
  if (a && a.drivingTimeSec > AUTO_PERIOD) f += '⚠';
  return f;
}

function buildTree() {
  const roots = new Map();
  for (const rec of PATHS.autos) {
    const first = rec.pathNames.find(p => p !== null) || '(none)';
    if (!roots.has(first)) roots.set(first, []);
    roots.get(first).push(rec);
  }
  const ordered = [...roots.entries()].sort((a, b) =>
    b[1].length - a[1].length || a[0].localeCompare(b[0]));

  const html = ordered.map(([root, recs]) => {
    const sample = AUTO_BY_NAME.get(recs[0].name);
    const y = sample && sample.samples.length ? sample.samples[0].y : null;
    const where = y === null ? '' : `starts ${LANE_LABEL[lane(y)]} (y≈${y.toFixed(1)})`;
    const pr = PATH_BY_NAME.get(root);
    const cls = pr ? pr.cls : 'grey';
    const leaves = recs
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(rec => {
        const a = AUTO_BY_NAME.get(rec.name);
        const t = a ? a.drivingTimeSec : null;
        const over = t !== null && t > AUTO_PERIOD;
        const chain = rec.pathNames.slice(1)
          .map(p => p === null ? '⟨null⟩' : p).join(' → ') || '—';
        const broken = rec.defects.includes('null-path');
        const time = t === null ? '?' :
          broken ? `${t.toFixed(1)}s (unreliable)` : `${t.toFixed(1)}s`;
        return `<div class="leaf" data-name="${rec.name}">
          <span class="flags">${autoFlags(rec.name)}</span>
          <b>${rec.name}</b>
          <span class="chain">→ ${chain}</span>
          <span class="t${over ? ' over' : ''}">${time}</span>
        </div>`;
      }).join('');
    return `<div class="root">
      <h3><span class="cls-${cls}">${root}</span>
        <span class="meta">${recs.length} auto${recs.length === 1 ? '' : 's'}${where ? ' · ' + where : ''}</span></h3>
      ${leaves}</div>`;
  }).join('');

  const el = document.getElementById('tab-tree');
  el.innerHTML = `<p class="src">✖ broken (null path) · ⧉ duplicate · 🧪 test · ⚠ over ${AUTO_PERIOD}s. Click any auto to see it on the Map.</p>` + html;
  el.addEventListener('click', e => {
    const d = e.target.closest('.leaf');
    if (d) isolate(d.dataset.name);
  });
}
```

Then call it — change the `.then` body so it reads `buildLegend(); buildRouteList(); buildTree();`

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_page_smoke.py -v`
Expected: PASS, 7 passed.

- [ ] **Step 6: Commit**

```bash
git add viewer/coverage.html tests/test_page_smoke.py
git commit -m "Add opening-path tree tab"
```

---

### Task 4: Audit tab

Every defect in the old 41, grouped, so deletions can be decided quickly.

**Files:**
- Modify: `viewer/coverage.html` (fill `#tab-audit`, add CSS and `buildAudit()`)
- Modify: `tests/test_page_smoke.py`

**Interfaces:**
- Consumes: `PATHS`, `AUTO_BY_NAME`, `AUTO_PERIOD`, `isolate()` from Tasks 1–2.
- Produces: `buildAudit()`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_page_smoke.py`:

```python
def test_audit_lists_every_defect_group(dom):
    for heading in ("Broken", "Duplicate", "Test", "Over", "Orphan"):
        assert heading in dom, heading


def test_audit_lists_all_18_orphan_paths(dom):
    for name in ("New New New New Path", "Whippin Awesome", "Flippin Awesome",
                 "10ft path", "five ft path", "Yes"):
        assert name in dom, name


def test_audit_names_both_overrun_autos(dom):
    assert "L Trench 2 Dip + Depot" in dom
    assert "21.7" in dom or "21.67" in dom
```

- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest tests/test_page_smoke.py -v -k audit`
Expected: FAIL — `#tab-audit` is empty.

- [ ] **Step 3: Add the CSS**

Insert before `</style>`:

```css
  /* ---- audit tab ---- */
  #tab-audit { flex-direction: column; overflow-y: auto; padding: 12px 16px; }
  .group { margin-bottom: 20px; max-width: 780px; }
  .group h3 { margin: 0 0 4px; font-size: 14px; }
  .group p { margin: 0 0 8px; font-size: 12px; color: var(--on-surface-dim); }
  .group ul { margin: 0; padding-left: 18px; }
  .group li { padding: 2px 0; font-size: 13px; cursor: pointer; }
  .group li:hover { color: var(--primary); }
  .group li.plain { cursor: default; }
  .group li.plain:hover { color: inherit; }
```

- [ ] **Step 4: Add `buildAudit()`**

Insert before the `Promise.all([` block:

```javascript
function buildAudit() {
  const byName = new Map(PATHS.autos.map(r => [r.name, r]));
  const withDefect = d => PATHS.autos.filter(r => r.defects.includes(d)).map(r => r.name).sort();

  const over = AUTOS.autos
    .filter(a => a.drivingTimeSec > AUTO_PERIOD)
    .sort((a, b) => b.drivingTimeSec - a.drivingTimeSec);

  const orphans = PATHS.paths.filter(p => p.cls === 'grey').map(p => p.name).sort();

  const groups = [
    ['Broken — auto references a null path',
     'PathPlanner cannot load these. They are in the chooser now, and their reported times are wrong because the generator skipped the null.',
     withDefect('null-path').map(n => `<li data-name="${n}">${n}</li>`)],

    ['Duplicate — identical command trees',
     'Same auto under two names. Keep one.',
     PATHS.duplicates.map(pair => `<li data-name="${pair[0]}">${pair[0]} ≡ ${pair[1]}</li>`)],

    ['Test / scratch entries',
     'These sit in the same chooser a driver picks from under pressure.',
     withDefect('junk').map(n => `<li data-name="${n}">${n}</li>`)],

    [`Over the ${AUTO_PERIOD}s auto period`,
     'Driving time alone, before any shooting. These cannot finish.',
     over.map(a => `<li data-name="${a.name}">${a.name} — ${a.drivingTimeSec.toFixed(2)}s</li>`)],

    ['Orphan paths — referenced by no auto',
     'Safe to delete from the paths folder.',
     orphans.map(n => `<li class="plain">${n}</li>`)],
  ];

  const el = document.getElementById('tab-audit');
  el.innerHTML = groups.map(([h, note, items]) => `
    <div class="group">
      <h3>${h} <span class="count">${items.length}</span></h3>
      <p>${note}</p>
      <ul>${items.join('') || '<li class="plain">none</li>'}</ul>
    </div>`).join('');
  el.addEventListener('click', e => {
    const li = e.target.closest('li[data-name]');
    if (li) isolate(li.dataset.name);
  });
}
```

Add `buildAudit();` to the `.then` body.

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_page_smoke.py -v`
Expected: PASS, 10 passed.

- [ ] **Step 6: Commit**

```bash
git add viewer/coverage.html tests/test_page_smoke.py
git commit -m "Add audit tab listing every defect in the old auto set"
```

---

### Task 5: Gap tab

The Friday checklist: three lanes, what exists, what does not.

**Files:**
- Modify: `viewer/coverage.html` (fill `#tab-gap`, add CSS and `buildGap()`)
- Modify: `tests/test_page_smoke.py`

**Interfaces:**
- Consumes: `AUTOS`, `PATHS`, `lane()`, `AUTO_PERIOD`, `isolate()`.
- Produces: `buildGap()`; module constant `TARGET_SET` naming the three new autos.

The three target autos do not exist yet, so the tab shows them as empty slots and lists the existing autos that start in each lane as rebuild candidates. A slot counts as filled when an auto whose name matches the target name appears in `autos.json`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_page_smoke.py`:

```python
def test_gap_shows_three_lane_slots(dom):
    for slot in ("Turret Left", "Turret Centre", "Turret Right"):
        assert slot in dom, slot


def test_gap_marks_unbuilt_slots(dom):
    text = re.sub(r"<[^>]+>", " ", dom)
    assert "not built" in text.lower()


def test_gap_lists_fallbacks(dom):
    assert "Simple Left" in dom and "Fast Away" in dom
```

- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest tests/test_page_smoke.py -v -k gap`
Expected: FAIL — `#tab-gap` is empty.

- [ ] **Step 3: Add the CSS**

Insert before `</style>`:

```css
  /* ---- gap tab ---- */
  #tab-gap { flex-direction: column; overflow-y: auto; padding: 12px 16px; }
  .slots { display: flex; flex-wrap: wrap; gap: 12px; max-width: 900px; }
  .slot {
    flex: 1 1 240px; background: var(--surface-1); border: 1px solid var(--outline);
    border-radius: 8px; padding: 12px;
  }
  .slot.todo { border-style: dashed; }
  .slot h3 { margin: 0 0 2px; font-size: 14px; }
  .slot .state { font-size: 12px; color: var(--warn-fg); }
  .slot.done .state { color: var(--green); }
  .slot ul { margin: 8px 0 0; padding-left: 16px; }
  .slot li { font-size: 12px; padding: 1px 0; cursor: pointer; }
  .slot li:hover { color: var(--primary); }
  .note { max-width: 780px; font-size: 12px; color: var(--on-surface-dim); margin-top: 16px; }
```

- [ ] **Step 4: Add `buildGap()`**

Insert before the `Promise.all([` block:

```javascript
const TARGET_SET = [
  { key: 'left',   name: 'Turret Left' },
  { key: 'centre', name: 'Turret Centre' },
  { key: 'right',  name: 'Turret Right' },
];
const FALLBACKS = ['Simple Left', 'Fast Away'];

function startLane(autoName) {
  const a = AUTO_BY_NAME.get(autoName);
  if (!a || !a.samples || !a.samples.length) return null;
  return lane(a.samples[0].y);
}

function buildGap() {
  const cards = TARGET_SET.map(slot => {
    const built = AUTO_BY_NAME.get(slot.name);
    const candidates = AUTOS.autos
      .filter(a => startLane(a.name) === slot.key)
      .filter(a => {
        const rec = PATHS.autos.find(r => r.name === a.name);
        return rec && rec.defects.length === 0;
      })
      .sort((a, b) => a.drivingTimeSec - b.drivingTimeSec)
      .slice(0, 6);

    const state = built
      ? `built — ${built.drivingTimeSec.toFixed(1)}s of ${AUTO_PERIOD}s`
      : 'not built';

    return `<div class="slot ${built ? 'done' : 'todo'}">
      <h3>${slot.name}</h3>
      <div class="state">${state}</div>
      <ul>${candidates.map(a =>
        `<li data-name="${a.name}">${a.name} — ${a.drivingTimeSec.toFixed(1)}s</li>`
      ).join('') || '<li>no clean candidates in this lane</li>'}</ul>
    </div>`;
  }).join('');

  const fb = FALLBACKS
    .filter(n => AUTO_BY_NAME.has(n))
    .map(n => `<li data-name="${n}">${n} — ${AUTO_BY_NAME.get(n).drivingTimeSec.toFixed(1)}s</li>`)
    .join('');

  document.getElementById('tab-gap').innerHTML = `
    <p class="src">Three new autos, one per lane, same script mirrored. Listed under each are
      existing defect-free routes starting in that lane, shortest first — rebuild candidates.</p>
    <div class="slots">${cards}</div>
    <div class="slot" style="max-width:340px;margin-top:16px">
      <h3>Keep as fallbacks</h3>
      <div class="state" style="color:var(--on-surface-dim)">valid on a turret without rework</div>
      <ul>${fb}</ul>
    </div>
    <p class="note"><b>Blocked on the shop:</b> <code>settings.json</code> still carries the
      pre-turret <code>robotMass 45.36</code> / <code>robotMOI 6.03</code>, and
      <code>driveCurrentLimit 35.0</code> is unresolved. Trajectories generated before those are
      corrected will not match the robot.</p>`;

  document.getElementById('tab-gap').addEventListener('click', e => {
    const li = e.target.closest('li[data-name]');
    if (li) isolate(li.dataset.name);
  });
}
```

Add `buildGap();` to the `.then` body.

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_page_smoke.py -v`
Expected: PASS, 13 passed.

- [ ] **Step 6: Commit**

```bash
git add viewer/coverage.html tests/test_page_smoke.py
git commit -m "Add gap tab for the three-auto turret target set"
```

---

### Task 6: Scouting bridge

Turn the scouting repo's team profiles into a small file the page can read.

**Files:**
- Create: `tools/build_scouting.py`
- Create: `tests/test_build_scouting.py`

**Interfaces:**
- Consumes: `../2026-Rocket-City-Scouting/exports/prescout_team_profiles.csv` (columns `team`, `name`, `n_matches`, `median_autoPoints`).
- Produces: `viewer/scouting.json`, relied on by Task 7 —
  ```json
  {
    "generatedAt": "...",
    "event": "2026wvrox",
    "home": 4265,
    "p75": 11.25,
    "median": 5.25,
    "teams": [{"team": 1325, "name": "...", "autoPoints": 36.0, "nMatches": 49}]
  }
  ```
  `teams` is sorted by `autoPoints` descending. Teams with no scouted value get `autoPoints: 0.0` and keep their real `nMatches` so the page can show sample size.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_build_scouting.py`:

```python
import pytest
from conftest import REPO
from tools.build_scouting import build, percentile

CSV = REPO.parent / "2026-Rocket-City-Scouting" / "exports" / "prescout_team_profiles.csv"

pytestmark = pytest.mark.skipif(not CSV.exists(), reason=f"no scouting export at {CSV}")


def test_percentile_matches_pandas_linear_interpolation():
    # pandas quantile(.75) on the real column returns 11.25
    assert percentile([0, 5, 10, 20], 0.75) == 12.5
    assert percentile([4], 0.75) == 4


def test_all_thirty_teams_present():
    assert len(build(CSV)["teams"]) == 30


def test_home_team_present():
    teams = {t["team"] for t in build(CSV)["teams"]}
    assert 4265 in teams


def test_sorted_descending_by_auto_points():
    pts = [t["autoPoints"] for t in build(CSV)["teams"]]
    assert pts == sorted(pts, reverse=True)


def test_top_of_field_is_1325_and_27_at_36():
    top = build(CSV)["teams"][:2]
    assert {t["team"] for t in top} == {1325, 27}
    assert all(t["autoPoints"] == 36.0 for t in top)


def test_measured_field_statistics():
    d = build(CSV)
    assert d["p75"] == 11.25
    assert d["median"] == 5.25


def test_sample_size_is_carried_so_zero_can_be_judged():
    home = next(t for t in build(CSV)["teams"] if t["team"] == 4265)
    assert home["nMatches"] == 20
    assert home["autoPoints"] == 0.0
```

- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest tests/test_build_scouting.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'tools.build_scouting'`

- [ ] **Step 3: Write the implementation**

Create `tools/build_scouting.py`:

```python
#!/usr/bin/env python3
"""Reduce the scouting repo's team profiles to what the coverage page needs.

Deliberately tiny: team, name, scouted median auto points, and the sample
size behind it. The sample size travels with the number because 4265's
0.0 over 20 matches is not yet confirmed to be real.

    python tools/build_scouting.py [path-to-prescout_team_profiles.csv]
"""
import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

EVENT = "2026wvrox"
HOME = 4265


def percentile(values, q):
    """Linear-interpolation percentile, matching pandas' default."""
    v = sorted(values)
    if not v:
        return 0.0
    if len(v) == 1:
        return float(v[0])
    pos = (len(v) - 1) * q
    lo = int(pos)
    hi = min(lo + 1, len(v) - 1)
    frac = pos - lo
    return float(v[lo] + (v[hi] - v[lo]) * frac)


def _num(s, default=0.0):
    try:
        return float(s)
    except (TypeError, ValueError):
        return default


def build(csv_path):
    teams = []
    with open(csv_path, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            teams.append({
                "team": int(_num(row["team"])),
                "name": (row.get("name") or "").strip(),
                "autoPoints": _num(row.get("median_autoPoints")),
                "nMatches": int(_num(row.get("n_matches"))),
            })
    teams.sort(key=lambda t: (-t["autoPoints"], t["team"]))
    pts = [t["autoPoints"] for t in teams]
    return {
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "event": EVENT,
        "home": HOME,
        "p75": percentile(pts, 0.75),
        "median": percentile(pts, 0.50),
        "teams": teams,
    }


def main():
    default = (Path(__file__).resolve().parents[1].parent
               / "2026-Rocket-City-Scouting" / "exports" / "prescout_team_profiles.csv")
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else default
    payload = build(src)
    out = Path(__file__).resolve().parents[1] / "viewer" / "scouting.json"
    out.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    print(f"{out}: {len(payload['teams'])} teams, "
          f"median {payload['median']}, p75 {payload['p75']}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_build_scouting.py -v`
Expected: PASS, 7 passed.

- [ ] **Step 5: Generate the data file**

Run: `python tools/build_scouting.py`
Expected: `.../viewer/scouting.json: 30 teams, median 5.25, p75 11.25`

- [ ] **Step 6: Commit**

```bash
git add tools/build_scouting.py tests/test_build_scouting.py viewer/scouting.json
git commit -m "Add scouting bridge for the WVRoX auto-points comparison"
```

---

### Task 7: Field tab

4265 against the 30 WVRoX teams, with the top-quartile line drawn as the target.

**Files:**
- Modify: `viewer/coverage.html` (fill `#tab-field`, add CSS and `buildField()`)
- Modify: `tests/test_page_smoke.py`

**REQUIRED SUB-SKILL:** load the `dataviz` skill before writing any chart code in this task.

**Interfaces:**
- Consumes: `viewer/scouting.json` from Task 6.
- Produces: `buildField()`. Fetch of `scouting.json` must **not** break the other four tabs if the file is absent — catch and render a one-line notice inside `#tab-field` only.

Chart form: a horizontal bar per team, sorted descending, 4265 bar in `--primary` and every other bar in `--secondary` at reduced opacity, with a vertical rule at `p75`. Horizontal bars because 30 team labels do not fit as rotated x-axis ticks on a phone. Plain DOM elements sized in percent — no canvas, no chart library.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_page_smoke.py`:

```python
def test_field_tab_renders_thirty_bars(dom):
    assert dom.count('class="bar"') == 30


def test_field_marks_the_top_quartile_line(dom):
    assert "11.25" in dom or "11.3" in dom


def test_field_highlights_4265(dom):
    assert 'data-team="4265"' in dom
    assert "class=\"bar home\"" in dom or 'bar home' in dom


def test_field_shows_sample_size_next_to_the_zero(dom):
    """4265's 0.0 is unconfirmed; the page must not state it bare."""
    text = re.sub(r"<[^>]+>", " ", dom)
    text = re.sub(r"\s+", " ", text)
    assert "20 matches" in text
```

- [ ] **Step 2: Run to verify they fail**

Run: `python -m pytest tests/test_page_smoke.py -v -k field`
Expected: FAIL — `#tab-field` is empty.

- [ ] **Step 3: Add the CSS**

Insert before `</style>`:

```css
  /* ---- field tab ---- */
  #tab-field { flex-direction: column; overflow-y: auto; padding: 12px 16px; }
  .chart { max-width: 720px; position: relative; }
  .row { display: grid; grid-template-columns: 96px 1fr 62px; gap: 8px; align-items: center; padding: 1px 0; }
  .row .who { font-size: 12px; text-align: right; color: var(--on-surface-dim);
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .row.home .who { color: var(--primary); font-weight: 700; }
  .track { background: var(--surface-2); border-radius: 3px; height: 14px; position: relative; }
  .bar { height: 100%; border-radius: 3px; background: var(--secondary); opacity: .55; }
  .bar.home { background: var(--primary); opacity: 1; }
  .row .val { font-size: 12px; color: var(--on-surface-dim); font-variant-numeric: tabular-nums; }
  .p75 { position: absolute; top: 0; bottom: 0; width: 0; border-left: 1px dashed var(--warn-fg); }
  .p75 span { position: absolute; top: -2px; left: 4px; font-size: 11px; color: var(--warn-fg); white-space: nowrap; }
  .caveat { max-width: 720px; font-size: 12px; color: var(--warn-fg);
    background: var(--warn-bg); border-radius: 6px; padding: 8px 10px; margin-top: 14px; }
```

- [ ] **Step 4: Add `buildField()`**

Insert before the `Promise.all([` block:

```javascript
function buildField(sc) {
  const el = document.getElementById('tab-field');
  if (!sc) {
    el.innerHTML = `<p class="src">scouting.json not found — run
      <code>python tools/build_scouting.py</code> to enable this tab.</p>`;
    return;
  }
  const max = Math.max(...sc.teams.map(t => t.autoPoints), 1);
  const home = sc.teams.find(t => t.team === sc.home);

  const rows = sc.teams.map(t => {
    const isHome = t.team === sc.home;
    const pct = (t.autoPoints / max) * 100;
    return `<div class="row${isHome ? ' home' : ''}" data-team="${t.team}">
      <div class="who">${t.team} ${t.name || ''}</div>
      <div class="track"><div class="bar${isHome ? ' home' : ''}" style="width:${pct.toFixed(1)}%"></div></div>
      <div class="val">${t.autoPoints.toFixed(1)}</div>
    </div>`;
  }).join('');

  // The rule sits over the bar column only: 96px label + 8px gap on the left,
  // 62px value + 8px gap on the right.
  const p75pct = (sc.p75 / max) * 100;
  el.innerHTML = `
    <p class="src">Scouted median auto points, ${sc.event}. Bar length is relative to the
      field's best (${max.toFixed(1)}).</p>
    <div class="chart">
      <div style="position:absolute;left:104px;right:70px;top:0;bottom:0;pointer-events:none">
        <div class="p75" style="left:${p75pct.toFixed(1)}%"><span>top quartile ${sc.p75}</span></div>
      </div>
      ${rows}
    </div>
    <p class="caveat"><b>Unconfirmed.</b> 4265 shows
      ${home ? home.autoPoints.toFixed(1) : '—'} across
      ${home ? home.nMatches : '—'} matches. With 41 autos in the repo that is either thin or
      miscoded scouting data, or a real finding. Confirm with a human before acting on it.</p>`;
}
```

Change the loader to fetch a fourth file and pass it through:

```javascript
Promise.all([
  fetch('autos.json').then(r => r.json()),
  fetch('paths.json').then(r => r.json()),
  fetch('source.json').then(r => r.json()).catch(() => null),
  fetch('scouting.json').then(r => r.json()).catch(() => null),
]).then(([autos, paths, src, sc]) => {
```

and add `buildField(sc);` to the `.then` body.

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_page_smoke.py -v`
Expected: PASS, 17 passed.

- [ ] **Step 6: Check it on a phone-width viewport**

Per the sized-iframe method — a headless `--window-size=390` lays out at 504 and crops, which fakes mobile bugs. Create `viewer/_phone.html` temporarily:

```html
<!doctype html><meta charset="utf-8">
<body style="margin:0;background:#111">
<iframe src="coverage.html" style="width:390px;height:844px;border:0"></iframe>
```

Screenshot it, confirm no horizontal scroll and that all five tabs are reachable, then delete the file.

- [ ] **Step 7: Commit**

```bash
git add viewer/coverage.html tests/test_page_smoke.py
git commit -m "Add field comparison tab with the top-quartile target line"
```

---

### Task 8: Wire it up and document it

**Files:**
- Modify: `viewer/index.html`
- Modify: `README.md`
- Create: `refresh.sh`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing downstream.

- [ ] **Step 1: Add a link from the menu**

In `viewer/index.html`, add a link to `coverage.html` alongside the existing team cards. Match the existing markup and classes in that file rather than introducing new ones — read it first and follow what is there.

- [ ] **Step 2: Add the regeneration script**

Create `refresh.sh`:

```bash
#!/usr/bin/env bash
# Regenerate the coverage page's data. Unlike regen.sh this needs no JDK,
# no clone and no Java — it only reads files that are already on disk.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python "$HERE/tools/build_paths.py" "$@"
python "$HERE/tools/build_scouting.py"
```

Then `chmod +x refresh.sh`.

- [ ] **Step 3: Document it in the README**

Add a `## Coverage page` section under the existing `## Pages` list covering: what `coverage.html` is for, the green/yellow/grey salvage rule and why a turret makes it valid, that `refresh.sh` regenerates `paths.json` and `scouting.json` without Java, and the two shop blockers (`robotMass`/`robotMOI` still pre-turret, `driveCurrentLimit 35.0` unresolved).

- [ ] **Step 4: Run the whole suite**

Run: `python -m pytest tests/ -v`
Expected: PASS, 37 passed.

- [ ] **Step 5: Commit**

```bash
git add viewer/index.html README.md refresh.sh
git commit -m "Link the coverage page from the menu and document it"
```

---

## Self-review notes

Checked against the spec:

- Five tabs — Tasks 2, 3, 5, 4, 7 in spec build order (paths.json → Map+Tree → Audit → Gap → Field). ✓
- Salvage classification with counts 36/31/18 — Task 1, asserted in `test_measured_ground_truth`. ✓
- Both null-path autos flagged and their durations marked untrustworthy — Task 1 detects, Task 3 renders `(unreliable)`, Task 4 states why. ✓
- Both duplicate pairs, six junk entries, two overruns — Task 4. ✓
- 3-auto target set plus the two fallbacks — Task 5, `TARGET_SET` and `FALLBACKS`. ✓
- p75 line at 11.25 — Task 7. ✓
- 4265's 0.0 not asserted as fact, sample size shown — Task 7, `test_field_shows_sample_size_next_to_the_zero`. ✓
- Shop blockers surfaced in the UI — Task 5's `.note`, and in the README at Task 8. ✓
- Phone support — Task 7 Step 6, using the sized-iframe method. ✓
- Never writes to `2026-Robot` — both generators open files read-only. ✓

Naming is consistent across tasks: `PATHS`, `AUTOS`, `PATH_BY_NAME`, `AUTO_BY_NAME`, `lane()`, `isolate()`, `showTab()`, `autoClass()`, `autoFlags()`, `startLane()`, `buildLegend()`, `buildRouteList()`, `buildTree()`, `buildAudit()`, `buildGap()`, `buildField()`. `cls` is the salvage class field name in `paths.json` throughout.

**Not covered by any task, deliberately:** TBA-derived auto OPR for the Field tab. The spec lists it as "a second pass if time allows"; with two days it is out. `scouting.json` carries only scouted medians, and Task 7 reads only those fields, so adding OPR later means extending the generator and one more bar colour — no rework of the page.
