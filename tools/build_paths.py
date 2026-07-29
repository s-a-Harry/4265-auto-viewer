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
