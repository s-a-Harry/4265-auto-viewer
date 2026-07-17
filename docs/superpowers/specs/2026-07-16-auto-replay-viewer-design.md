# FRC 4265 Auto Replay Viewer — Design

**Date:** 2026-07-16
**Status:** MVP built. See README for what actually shipped.

> **MVP deviations from this design.** The core holds — the generator uses the real
> PathPlannerLib, the viewer does no physics, all 41 autos generate, folders group them, source
> SHA is stamped. Not yet built:
>
> - **Generator is injected into a throwaway clone** of `2026-Robot` by `regen.sh` rather than
>   being a standalone Gradle project. GradleRIO already resolves PathPlannerLib and the WPILib
>   natives there; reproducing that resolution standalone was not worth it for an MVP.
> - **No CI.** `regen.sh` is manual, so the staleness mitigation is only the visible SHA stamp.
> - **Field is boundary + centre line + 1 m grid.** No REBUILT elements drawn.
> - **Robot is a square + heading line.** `robotFeatures` (intake, turret) not rendered.
> - **Golden test not written.** Timings remain unvalidated against the PathPlanner GUI.
> - Data lives in `viewer/autos.json`, not `data/`. Field size comes from
>   `FlippingUtil.fieldSizeX/Y` (16.54 × 8.07) rather than the field drawings.

## Problem

Team 4265 has 41 PathPlanner autonomous routines in `2026-Robot`. There is no way to see
what any of them do without opening the PathPlanner GUI on a machine with the repo cloned.

The original request was to "replay the PathPlanner videos." No such videos exist — not on
4265's channels, nor anywhere else. **PathPlanner has no video export.** The animation in the
GUI is a live preview rendered on the fly from path data and never captured to a file. Teams
with auto footage either screen-recorded the GUI or filmed the robot.

The underlying data, however, is public and is strictly better than video: it scrubs, it stays
in sync when a path is re-tuned, and it needs no manual recording step. This app rebuilds the
preview from that data.

## Audience

**Drive team and strategy.** People who know the field. Consequences:

- Timing fidelity is a hard requirement. A replay with invented timing is worse than nothing,
  because it implies an auto fits in the 20-second autonomous period when it may not.
  (2026 REBUILT autonomous is **20 s**, not the customary 15 s.)
- Visual polish is secondary.
- All 41 autos are shown, including `Testing`, grouped by folder.

## Scope

### In scope

- All 41 autos from `2026-Robot@origin/dev`, grouped by their existing PathPlanner folders.
- Physics-faithful trajectory playback: pose, heading, velocity over time.
- Scrub, play/pause, speed control.
- Robot rendered with real geometry from `robotFeatures` (intake, turret, frame outlines).
- Driving time per auto, explicitly labeled as excluding command duration.
- Source commit SHA + date displayed in the UI.

### Out of scope (YAGNI)

- Named-command durations. Not in the path data. Driving time is shown as a floor. (See
  "Known gap" below.)
- Match log replay of actual robot behavior.
- Path editing. This is a viewer.
- Choreo autos (`choreoAuto: false` across the board today).

## Source of truth

**`2026-Robot`, branch `origin/dev`.**

| | `main` | `dev` |
|---|---|---|
| Autos | 29 | **41** |
| Last commit | 2026-03-23 | **2026-06-23** |
| Ahead of the other | 0 commits | **39 commits** |

`dev` is the repo default (`origin/HEAD`) and a strict superset of `main` — `main` is 0 commits
ahead, so tracking `dev` loses nothing. `main` is a competition-season snapshot; `dev` carries
offseason work.

### Robot config

Generation uses `2026-Robot`'s `src/main/deploy/pathplanner/settings.json`:

```
robotMass         45.36 kg      driveGearing       5.27
robotMOI          6.03          maxDriveSpeed      6.06 m/s
robotWidth/Length 0.8509 m      driveMotorType     krakenX60 (non-FOC)
robotTrackwidth   0.546 m       driveCurrentLimit  35.0 A
driveWheelRadius  0.051 m       wheelCOF           1.2
```

**Do not use `2026-Robot-Simulation`'s config.** It diverges materially (mass 75.0, MOI 6.883,
krakenX60**FOC**, gearing 7.36, current limit **120 A**, COF 1.7, frame 0.762). These are the
physics inputs to trajectory generation; the current-limit difference alone is 3.4× in available
torque and would change every reported time.

**Open flag for the team (not blocking this app):** a 35 A drive current limit is unusually low
— most swerve drives run 60–120 A, and 4265's own sim repo uses 120. If 35 A is stale or a typo
in `dev`, PathPlanner has been generating over-conservative trajectories on the real robot all
season, and the real autos may be planned slower than the hardware can achieve. The app uses the
value as-is and therefore reflects real current behavior either way. Worth investigating
independently.

## Architecture

Two halves, one interface between them.

```
2026-Robot@dev ──clone──> generator (Java/Gradle) ──> autos.json ──fetch──> viewer (static)
                              │                                                  │
                          PathPlannerLib-2026.1.2                          canvas playback
                          + settings.json                                  (no physics)
```

**The viewer does no physics.** It never computes a trajectory, so it cannot disagree with the
robot. All fidelity lives in the generator, which delegates to the same
`PathPlannerLib-2026.1.2` the robot runs. When the library changes next season, bump one
dependency.

### Why precompute rather than port

PathPlannerLib 2025+ does physics-based trajectory generation — it solves against motor torque
curves and friction limits, not just velocity/accel caps. The robot decelerates into a turn
because the wheels would slip; it accelerates at the rate the Krakens deliver at the configured
current limit.

Reimplementing that in TypeScript was rejected: it is weeks of work, it drifts from a library
that changes every season, and when it drifts it produces plausible-looking wrong timings — the
worst failure mode for a strategy tool. Precomputing is both less work and more accurate.

Constant-velocity spline interpolation was rejected outright: the timing would be fiction.

## Components

- **`generator/`** — Gradle project, depends on PathPlannerLib + WPILib.
  `TrajectoryDumper.java` walks each auto's command tree, resolves `path` nodes to `.path`
  files, chains segments in sequence, generates trajectories, emits JSON.
- **`data/autos.json`** — generated artifact, **committed** so the site builds and serves with
  no Java toolchain present.
- **`viewer/`** — static HTML/TS.
  - `field.ts` — REBUILT field geometry (16.5 m × 8.2 m) from official field dimension drawings.
  - `robot.ts` — renders `robotFeatures` (intake, turret, frame outlines) so heading and intake
    orientation are readable.
  - `player.ts` — scrub, play/pause, speed.
  - `list.ts` — folder grouping; `Testing` collapsed by default.
- **`.github/workflows/regen.yml`** — nightly + manual (`workflow_dispatch`).

## Data model

Per auto:

```jsonc
{
  "name": "Bumpy Ride L-R",
  "folder": "Bumpy",
  "drivingTimeSec": 12.4,        // floor; excludes named-command duration
  "samples": [                    // ~50 Hz
    { "t": 0.02, "x": 3.37, "y": 7.41, "heading": 0.0, "vel": 0.8 }
  ],
  "eventMarkers": [ { "t": 4.1, "name": "shoot" } ],
  "error": null                   // populated if generation failed
}
```

Top level carries `sourceSha`, `sourceCommitDate`, `generatedAt`.

## Staleness

A standalone repo was chosen over hosting inside `2026-Robot`. The known risk is the generated
data going stale relative to the paths. Two mitigations, neither requiring changes to
`2026-Robot`:

1. **The generator clones `2026-Robot` fresh each run** and stamps the source commit SHA and
   date into the output. The viewer displays it ("paths as of `550d245`, 2 days ago"), so no one
   is misled about freshness.
2. **Scheduled CI** — nightly, plus manual trigger.

## Error handling

Generation is all-or-nothing **per auto, not per run.** If one auto fails (broken path
reference, missing named command), it is emitted with its `error` populated and the other 40
still ship. The viewer shows it greyed out with the reason.

Rationale: a missing auto is a silent lie; a broken one is a bug report. The `Testing` folder
(`Awesome`, `Not Awesome`, `EventTest`, `SysCheck`) contains the likely offenders.

## Testing

- **Unit** — the auto/path parser against fixtures already present in
  `2026-Robot-Simulation` (`SimplePath.path`, `Tests.auto`): small, known, checked in.
- **Golden (the real check)** — generate a known auto and assert its total driving time against
  what the PathPlanner GUI reports for the same auto. If those agree, the pipeline is honest.
  **Requires a human to read one number off the GUI.** Until this passes, the app's timings are
  unverified and must not be presented to the drive team as authoritative.

## Known gap: command duration

Named commands have no duration in PathPlanner data. An auto's command tree interleaves `path`
nodes with `named` nodes; PathPlanner knows the driving time but not that a shoot command takes
1.2 s.

**Decision:** show driving time only, labeled "path time, excludes commands." It is an honest
floor, not a real total. Annotating durations in a config file was considered and deferred until
the estimates prove necessary.

This means **the app cannot answer "does this auto fit in the 20-second auto period."** It can only answer
"the driving alone takes at least N seconds." That limit must be legible in the UI, not buried.

## Decisions

| Decision | Choice | Rejected |
|---|---|---|
| Auto set | All 41, grouped by folder | Curated subset; auto-filtered |
| Audience | Drive team / strategy | Outreach; both |
| Trajectory source | Precompute w/ real PathPlannerLib | TS port; constant-velocity |
| Command duration | Driving time only, labeled | Annotated config; log-derived |
| Config source | `2026-Robot` as-is (35 A included) | Sim repo; corrected values |
| Branch | `origin/dev` | `main` (stale, subset) |
| Hosting | Standalone repo | In `2026-Robot` + Pages; local-only |
