#!/usr/bin/env bash
# Generate a viewer dataset for ANOTHER team by borrowing 4265's build.
#
#   ./generate-team.sh <owner/repo> <teamNumber>
#   ./generate-team.sh FRC2539/2026-rebuilt 2539
#
# Their pathplanner folder is swapped into the 2026-Robot clone and run through
# the same AutoDumper, so trajectories use THEIR robot config (mass, motors,
# current limit) but the identical PathPlannerLib. Only meaningful for 2026 autos
# — a 2025 auto is plotted on a different field and will land in the wrong place.
set -euo pipefail

REPO="${1:?usage: generate-team.sh <owner/repo> <teamNumber>}"
NUM="${2:?usage: generate-team.sh <owner/repo> <teamNumber>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROBOT="$HERE/../2026-Robot"
PP="src/main/deploy/pathplanner"

export JAVA_HOME="${JAVA_HOME:-/c/Users/Public/wpilib/2026/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

[ -d "$ROBOT" ] || { echo "expected 4265 clone at $ROBOT (run ./regen.sh first)"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Cloning $REPO ..."
git clone --depth 1 "https://github.com/$REPO.git" "$TMP/repo" >/dev/null 2>&1

SRC="$TMP/repo/$PP"
[ -d "$SRC/autos" ] || { echo "no $PP/autos in $REPO — not a PathPlanner project?"; exit 1; }

# Confirm the repo really belongs to team NUM before trusting its data.
ACTUAL=$(grep -o '"teamNumber"[^,}]*' "$TMP/repo/.wpilib/wpilib_preferences.json" 2>/dev/null | grep -o '[0-9]\+' || true)
if [ -n "$ACTUAL" ] && [ "$ACTUAL" != "$NUM" ]; then
  echo "WARNING: repo reports team $ACTUAL, not $NUM. Aborting."; exit 1
fi

echo "Generating team $NUM from $REPO ..."
( cd "$ROBOT"
  rm -rf "$PP"
  cp -r "$SRC" "$PP"
  ./gradlew dumpAutos -Pout="$HERE/viewer/autos-$NUM.json" --console=plain -q
  # restore 4265's own paths; git clean removes the other team's untracked leftovers
  git checkout -- "$PP"
  git clean -fdq "$PP"
)

echo "Wrote viewer/autos-$NUM.json"
echo "Add it to viewer/teams.json with a distinct colour to show it in the alliance view."
