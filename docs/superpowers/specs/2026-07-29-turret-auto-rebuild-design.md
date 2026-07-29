# Turret Auto Rebuild — Coverage & Salvage Page — Design

**Date:** 2026-07-29
**Status:** Approved, not yet built.
**Deadline:** WVRoX offseason, TBA `2026wvrox`, **2026-07-31 / 08-01**, Fairmont State University, WV.

## Problem

4265 rebuilt the robot to a **turret-style shooter**. The 41 PathPlanner autos in
`2026-Robot` were all drawn for the previous fixed-shooter robot, where the drivetrain had to
rotate the whole robot to aim. A turret decouples heading from scoring, which invalidates the
autos but **not** the routes:

- **Routes survive.** Driving from the right wall to the outpost is the same drive regardless of
  where the shooter points. The `.path` geometry is still good.
- **Headings and command trees do not.** Rotation targets that existed to aim a fixed shooter are
  now pure wasted time, and stop-and-shoot command trees are replaced by shoot-while-moving.

Nobody can currently see which routes are salvageable, what the new set needs to cover, or what
bar the new autos have to clear. This page answers those three questions in one place.

## Verified state (2026-07-29)

Facts below were measured, not assumed. Anything unverified is called out as such.

**Branches.** `dev` is at `550d245` (2026-06-23). `origin/WVROX-prep` (`d60e6e3`, 2026-07-13) is
**2 commits ahead of `dev`, 0 behind**, and changes **nothing** under
`src/main/deploy/pathplanner/`. So no new autos have been drawn for the turret robot yet, and the
analysis below holds for both branches.

**Autos.** 41 `.auto` files. Start poses fall into lanes:

| lane | y (m) | autos |
|---|---|---|
| left wall | ≈ 7.4 | 14 |
| right wall | ≈ 0.6–0.7 | 13 |
| centre | 2.6 – 6.0, scattered | 14 |

**Paths.** 85 `.path` files, **67 referenced** by some auto, **18 orphaned**.

**Defects found in the existing set:**

- **2 autos reference a `null` path** mid-sequence — `S R Back to Middle NC` and
  `Shoot 8 R Trench 2 Dips`. PathPlanner cannot load a null path name; these are broken autos
  sitting in the driver's chooser. The existing viewer silently skipped the null and still
  reported times (9.554 s / 16.062 s), so **those two durations are wrong**.
- **2 exact duplicates** (identical command trees): `New Auto` ≡ `Simple L NC`, and
  `Back up Simple Right + Outpost` ≡ `Simple Right + Outpost`.
- **6 test/junk entries** in the same chooser a driver picks from: `Awesome`, `Not Awesome`,
  `EventTest`, `SysCheck`, `New Auto`, `New New Auto`.
- **2 exceed the 20 s auto period on driving time alone**: `45 Defensive` 21.667 s,
  `L Trench 2 Dip + Depot` 20.741 s. (2026 REBUILT auto is **20 s**, not the customary 15 s.)
- **18 orphan paths**, several obviously scratch: `New Path`, `New New Path`,
  `New New New Path`, `New New New New Path`, `Yes`, `Whippin Awesome`, `Flippin Awesome`,
  `Copy of Circle`, `10ft path`, `five ft path`.

## Decision: build 3 new autos + keep 2 fallbacks

The WVRoX field is weak in autonomous. Across the 30 attending teams, scouted median auto points:

| statistic | value |
|---|---|
| teams scoring 0.0 | **8** |
| field median | **5.25** |
| 75th percentile | **11.25** |
| best (1325, 27) | **36.0** |

**One auto that reliably scores ~11 points reaches the top quartile of this event.** The bar is a
single good cycle. That is the argument against rebuilding 41 and against building 8.

**Three new autos** — left, centre, right — because the alliance decides which lane 4265 gets and
that is not controllable. All three run the *same script* (leave → shoot while moving → pick up →
shoot), mirrored, so tuning transfers between them.

This script assumes the turret can track and fire **while the drivetrain is moving**. If turret
tracking is not tuned for that by Friday, the script degrades to shooting at stationary
waypoints — same three routes, same lanes, extra time per shot. That degradation is a tuning
outcome, not a redesign, and does not change anything on this page.

**Two fallbacks** kept from the old set: `Simple Left` (5.441 s) and `Fast Away` (2.431 s). Both
barely rotate, so they remain valid on a turret robot with no rework.

## Salvage classification

Mechanical, derived from the files — not a judgment call. Of the **67 used** paths:

| class | rule | count | action |
|---|---|---|---|
| **Green** | no `rotationTargets` | **36** | reuse as-is; geometry only |
| **Yellow** | has `rotationTargets` | **31** | route is fine — delete the targets, path gets *faster* |
| **Grey** | referenced by no auto | **18** | delete |

Counts reconcile as 36 + 31 = 67 used, and 67 + 18 = 85 files. Note that the `null` path
reference described above is **not** a path file and must be excluded when computing these
counts, or the totals will overshoot by one.

Start and end rotations (`idealStartingState.rotation`, `goalEndState.rotation`) are shown per
path but are **not** used for classification, because an end rotation may serve intake alignment
rather than shooting. The page reports them; a human decides.

## The page

New `viewer/coverage.html` in `4265-auto-viewer`, alongside the existing
`index` / `single` / `alliance` pages, served by the existing `java Serve.java 8265 viewer`.

It lives here because this repo already has the field image, the path renderer, PathPlanner's
visual language, phone support, and all four teams' trajectory data. A fresh app would mean
rebuilding the renderer; merging into the scouting dashboard would mean dragging 1.4 MB of
trajectories into a repo with no field view.

### Tab 1 — Map

Every route drawn on one field at once, coloured by salvage class (green / yellow / grey).
Filter by class, start lane, and folder. Hover names a route; click isolates it with its
velocity colouring and driving time. Reuses the existing renderer and field image.

### Tab 2 — Tree

Opening-path branching, rooted at the first path each auto drives — `R Trench` opens 8 autos,
`L Trench` opens 5. Branches on subsequent path names; leaves are autos with their duration and
flags: ⚠ over 20 s, ⧉ duplicate, 🧪 test, ✖ broken (null path). Clicking a leaf isolates it on
the Map.

### Tab 3 — Gap map

The 3-lane target set for the turret rebuild: left / centre / right, each showing whether a new
auto exists yet, its measured driving time against the 20 s budget, and which salvaged routes it
was built from. This is the Friday-morning checklist.

### Tab 4 — Audit

The old 41 as fallback candidates, with every defect from "Verified state" listed and linked to
the auto: broken, duplicate, junk, overrun. Purpose is deletion decisions, not nostalgia.

### Tab 5 — Field

4265 against the 30 WVRoX teams on auto points, with the **11.25 top-quartile line** drawn on the
chart so the target is explicit. Scouted median first; TBA-derived auto OPR as a second pass if
time allows.

## Data

**Tabs 1–4 require no new data generation.** `viewer/autos.json` already carries, per auto:
`name`, `folder`, an **ordered list of path names**, `drivingTimeSec`, `eventMarkers`,
`commands`, and full `samples` (`t, x, y, deg, v`). The tree, lanes, and durations are all
derivable client-side. Salvage classification needs one new field — whether each path has
rotation targets — added by a small extension to the existing generator, or by a standalone
Python pass over `paths/*.path` that writes `viewer/paths.json`. **Prefer the Python pass:** it
needs no WPILib JDK, no throwaway clone, and no Java, which matters with two days left.

**Tab 5 requires one new file.** `viewer/scouting.json`, generated from
`2026-Rocket-City-Scouting/exports/prescout_team_profiles.csv` — team, name, `median_autoPoints`,
`n_matches` for the 30 WVRoX teams — and copied into `viewer/`. Small and static.

Other teams' trajectories (`autos-117.json`, `autos-1466.json`, `autos-2539.json`) already exist
and are unchanged by this work.

## Build order

Ordered so that a stop at any point still leaves something usable at the event.

1. `paths.json` generator (Python, no Java) — salvage classification.
2. Tab 1 Map + Tab 2 Tree — the "see the paths" requirement, off data that already exists.
3. Tab 4 Audit — deletion decisions; enables cleaning the chooser before the event.
4. Tab 3 Gap map — becomes useful as new autos land.
5. Tab 5 Field — needs the `scouting.json` bridge. Ships with scouted median alone if auto OPR
   is not wired up in time.

## Blockers outside this repo

Neither is code, both are on the critical path, and this page cannot fix either:

- **`settings.json` still describes the old robot** — `robotMass: 45.36`, `robotMOI: 6.03`.
  A turret rebuild changes mass and moment of inertia. Every trajectory PathPlanner generates
  is wrong until the new robot is weighed and the MOI re-estimated.
- **`driveCurrentLimit: 35.0`** — open since 2026-07-16 and now urgent. Most swerve drives run
  60–120 A; 4265's own `2026-Robot-Simulation` uses 120. If 35 is stale, every new path will be
  generated for a robot slower than the hardware actually is.

## Needs human verification

- **4265's scouted `median_autoPoints` is 0.0 across 20 matches**, and `median_totalPoints` reads
  14.0, which is also implausibly low. Either the scouting data for 4265 is thin or miscoded, or
  it is a real finding. **Tab 5 must not assert this as fact until a human confirms it** — show
  the sample size beside the number.
- **Auto durations remain unvalidated** against the PathPlanner GUI, carried over from the
  2026-07-16 design. The two null-path autos are now known to be wrong specifically.

## Non-goals

- Drawing or tuning the new paths. That is PathPlanner GUI work at the shop, not browser work.
- Any physics or trajectory simulation in the page. It plays back precomputed samples; that is
  what keeps it from drifting from the robot.
- Editing `2026-Robot`. This repo reads that one and never writes to it.
- Pulling additional teams' repos. GitHub is TLS-intercepted on this network, and the three
  existing teams are enough.
- Rebuilding all 41 autos.

## Verification

- `paths.json` classification counts reproduce the measured figures: 67 used (36 green,
  31 yellow), 18 orphaned, 85 total — and 36 + 31 + 18 = 85 exactly.
- Both null-path autos appear flagged ✖ in Tab 2 and Tab 4, and their durations are marked
  untrustworthy rather than displayed plainly.
- Both duplicate pairs appear as ⧉ in Tab 4.
- `45 Defensive` and `L Trench 2 Dip + Depot` appear as ⚠ against the 20 s budget.
- Every one of the 41 autos is reachable from the Tree, and every leaf click isolates the right
  route on the Map.
- Tab 5 shows 30 teams, with 4265 highlighted and the 11.25 line drawn.
- Page renders on a phone — carried over as a default from the existing viewer.
