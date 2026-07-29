from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

REPO = Path(__file__).resolve().parents[1]
ROBOT = REPO.parent / "2026-Robot"
PPLAN = ROBOT / "src" / "main" / "deploy" / "pathplanner"
