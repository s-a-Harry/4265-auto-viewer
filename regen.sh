#!/usr/bin/env bash
# Regenerate viewer/autos.json from the robot repo.
#
#   ./regen.sh [path-to-2026-Robot]
#
# Clones/updates 2026-Robot@dev, injects the dumper into it, runs trajectory
# generation with the real PathPlannerLib, and copies the result into viewer/.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROBOT="${1:-$HERE/../2026-Robot}"
BRANCH="dev"

export JAVA_HOME="${JAVA_HOME:-/c/Users/Public/wpilib/2026/jdk}"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d "$ROBOT" ]; then
  echo "Cloning 2026-Robot -> $ROBOT"
  git clone --branch "$BRANCH" https://github.com/secret-city-wildbots/2026-Robot.git "$ROBOT"
else
  echo "Updating $ROBOT"
  git -C "$ROBOT" fetch origin "$BRANCH" --quiet
  git -C "$ROBOT" checkout "$BRANCH" --quiet
  git -C "$ROBOT" pull --ff-only --quiet
fi

# Inject the dumper. The robot repo is treated as disposable scratch space:
# nothing here is meant to be committed back to it.
mkdir -p "$ROBOT/src/main/java/frc/tools"
cp "$HERE/generator/AutoDumper.java" "$ROBOT/src/main/java/frc/tools/"

if ! grep -q "task dumpAutos" "$ROBOT/build.gradle"; then
  cat >> "$ROBOT/build.gradle" <<'EOF'

// --- injected by 4265-auto-viewer/regen.sh; not part of robot deploy ---
task dumpAutos(type: JavaExec) {
    group = "tools"
    description = "Dump all PathPlanner autos to JSON trajectory samples"
    dependsOn "extractReleaseNative"
    classpath = sourceSets.main.runtimeClasspath
    mainClass = "frc.tools.AutoDumper"
    args = [(project.findProperty("out") ?: "build/autos.json").toString()]
    doFirst {
        // build.gradle caps all JavaExec at -Xmx64m/-Xss256k; trajectory gen needs more.
        // WPILib JNI (ntcore/wpimath) must be on java.library.path.
        jvmArgs = ["-Xmx2g", "-Xss4m",
                   "-Djava.library.path=" + file("build/jni/release").absolutePath]
    }
}
EOF
  echo "Injected dumpAutos task"
fi

(cd "$ROBOT" && ./gradlew dumpAutos --console=plain -q)

cp "$ROBOT/build/autos.json" "$HERE/viewer/autos.json"

SHA=$(git -C "$ROBOT" rev-parse --short HEAD)
DATE=$(git -C "$ROBOT" log -1 --format=%cd --date=short)
cat > "$HERE/viewer/source.json" <<EOF
{
  "sha": "$SHA",
  "commitDate": "$DATE",
  "branch": "$BRANCH",
  "repo": "secret-city-wildbots/2026-Robot"
}
EOF

echo "Done. viewer/autos.json <- 2026-Robot@$SHA ($DATE)"
