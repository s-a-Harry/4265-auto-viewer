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
