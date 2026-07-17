package frc.tools;

import com.pathplanner.lib.config.RobotConfig;
import com.pathplanner.lib.events.Event;
import com.pathplanner.lib.events.OneShotTriggerEvent;
import com.pathplanner.lib.events.TriggerEvent;
import com.pathplanner.lib.path.PathPlannerPath;
import com.pathplanner.lib.trajectory.PathPlannerTrajectory;
import com.pathplanner.lib.trajectory.PathPlannerTrajectoryState;
import com.pathplanner.lib.util.FlippingUtil;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dumps every PathPlanner auto in this project to one JSON file of sampled trajectory
 * states, using the same PathPlannerLib the robot runs.
 *
 * Must run with the 2026-Robot project root as the working directory so that
 * Filesystem.getDeployDirectory() resolves to src/main/deploy.
 */
public class AutoDumper {
  private static final String PP_DIR = "src/main/deploy/pathplanner";
  private static final double SAMPLE_DT = 0.02;

  public static void main(String[] args) throws Exception {
    String outPath = args.length > 0 ? args[0] : "build/autos.json";

    RobotConfig config = RobotConfig.fromGUISettings();
    System.out.println("Robot config: mass=" + config.massKG + "kg MOI=" + config.MOI
        + " modules=" + config.numModules);

    List<Path> autoFiles = new ArrayList<>();
    try (var s = Files.list(Paths.get(PP_DIR, "autos"))) {
      s.filter(p -> p.toString().endsWith(".auto")).forEach(autoFiles::add);
    }
    autoFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
    System.out.println("Found " + autoFiles.size() + " autos");

    List<String> entries = new ArrayList<>();
    int ok = 0, failed = 0;

    for (Path autoFile : autoFiles) {
      String name = autoFile.getFileName().toString().replace(".auto", "");
      String folder = "Ungrouped";
      try {
        JSONObject root = readJson(autoFile);
        Object f = root.get("folder");
        if (f != null) folder = f.toString();
        entries.add(dumpAuto(root, name, folder, config));
        ok++;
      } catch (Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        System.out.println("  FAIL " + name + " -> " + msg);
        entries.add("    {\"name\": " + q(name) + ", \"folder\": " + q(folder)
            + ", \"paths\": [], \"drivingTimeSec\": 0"
            + ", \"error\": " + q(msg) + ", \"eventMarkers\": [], \"samples\": []}");
        failed++;
      }
    }

    StringBuilder out = new StringBuilder();
    out.append("{\n");
    out.append("  \"generatedAt\": ").append(q(java.time.Instant.now().toString())).append(",\n");
    out.append("  \"robot\": {\"massKG\": ").append(config.massKG)
        .append(", \"moi\": ").append(config.MOI)
        .append(", \"widthM\": ").append(settingsNum("robotWidth"))
        .append(", \"lengthM\": ").append(settingsNum("robotLength"))
        .append(", \"features\": ").append(robotFeatures())
        .append("},\n");
    // Field size straight from PathPlannerLib rather than a hardcoded guess.
    out.append("  \"field\": {\"sizeX\": ").append(FlippingUtil.fieldSizeX)
        .append(", \"sizeY\": ").append(FlippingUtil.fieldSizeY)
        .append("},\n");
    out.append("  \"autos\": [\n").append(String.join(",\n", entries)).append("\n  ]\n}\n");

    Path op = Paths.get(outPath);
    if (op.getParent() != null) Files.createDirectories(op.getParent());
    try (PrintWriter w = new PrintWriter(op.toFile())) {
      w.print(out);
    }
    System.out.println("Wrote " + outPath + " (" + ok + " ok, " + failed + " failed)");
  }

  private static String dumpAuto(JSONObject root, String name, String folder, RobotConfig config)
      throws Exception {
    List<String> pathNames = new ArrayList<>();
    Object cmd = root.get("command");
    if (cmd instanceof JSONObject) collectPaths((JSONObject) cmd, pathNames);
    if (pathNames.isEmpty()) throw new IllegalStateException("auto references no paths");

    List<String> samples = new ArrayList<>();
    List<String> markers = new ArrayList<>();
    double tOffset = 0.0;
    ChassisSpeeds speeds = new ChassisSpeeds();
    Rotation2d rot = null;

    for (String pathName : pathNames) {
      PathPlannerPath path = PathPlannerPath.fromPathFile(pathName);
      if (rot == null) {
        rot = path.getIdealStartingState() != null
            ? path.getIdealStartingState().rotation()
            : Rotation2d.kZero;
      }

      PathPlannerTrajectory traj = path.generateTrajectory(speeds, rot, config);
      List<PathPlannerTrajectoryState> states = traj.getStates();
      if (states.isEmpty()) continue;

      double total = traj.getTotalTimeSeconds();
      for (double t = 0; t <= total + 1e-9; t += SAMPLE_DT) {
        PathPlannerTrajectoryState st = sampleAt(states, Math.min(t, total));
        samples.add("{\"t\": " + r3(tOffset + t)
            + ", \"x\": " + r3(st.pose.getX())
            + ", \"y\": " + r3(st.pose.getY())
            + ", \"deg\": " + r3(st.pose.getRotation().getDegrees())
            + ", \"v\": " + r3(st.linearVelocity) + "}");
      }

      for (Event ev : traj.getEvents()) {
        String evName = null;
        if (ev instanceof OneShotTriggerEvent) {
          evName = ((OneShotTriggerEvent) ev).getEventName();
        } else if (ev instanceof TriggerEvent && ((TriggerEvent) ev).getValue()) {
          evName = ((TriggerEvent) ev).getEventName();
        }
        if (evName != null) {
          markers.add("{\"t\": " + r3(tOffset + ev.getTimestampSeconds())
              + ", \"name\": " + q(evName) + "}");
        }
      }

      PathPlannerTrajectoryState end = states.get(states.size() - 1);
      // generateTrajectory wants ROBOT-relative starting speeds (it converts to field-relative
      // itself); state.fieldSpeeds is field-relative, so convert before chaining.
      speeds = ChassisSpeeds.fromFieldRelativeSpeeds(end.fieldSpeeds, end.pose.getRotation());
      rot = end.pose.getRotation();
      tOffset += total;
    }

    return "    {\"name\": " + q(name)
        + ", \"folder\": " + q(folder)
        + ", \"paths\": " + qList(pathNames)
        + ", \"drivingTimeSec\": " + r3(tOffset)
        + ", \"error\": null"
        + ", \"eventMarkers\": [" + String.join(", ", markers) + "]"
        + ", \"samples\": [" + String.join(", ", samples) + "]}";
  }

  /** states are time-ordered; linear scan is fine at this scale. */
  private static PathPlannerTrajectoryState sampleAt(List<PathPlannerTrajectoryState> states, double t) {
    if (t <= states.get(0).timeSeconds) return states.get(0);
    for (int i = 1; i < states.size(); i++) {
      if (states.get(i).timeSeconds >= t) {
        PathPlannerTrajectoryState a = states.get(i - 1), b = states.get(i);
        double span = b.timeSeconds - a.timeSeconds;
        double frac = span <= 1e-9 ? 0 : (t - a.timeSeconds) / span;
        return a.interpolate(b, frac);
      }
    }
    return states.get(states.size() - 1);
  }

  private static void collectPaths(JSONObject cmd, List<String> out) {
    String type = (String) cmd.get("type");
    JSONObject data = (JSONObject) cmd.get("data");
    if (data == null) return;
    if ("path".equals(type)) {
      Object pn = data.get("pathName");
      if (pn != null) out.add(pn.toString());
      return;
    }
    Object children = data.get("commands");
    if (children instanceof JSONArray) {
      for (Object c : (JSONArray) children) {
        if (c instanceof JSONObject) collectPaths((JSONObject) c, out);
      }
    }
  }

  private static JSONObject readJson(Path p) throws Exception {
    try (BufferedReader r = new BufferedReader(new FileReader(p.toFile()))) {
      return (JSONObject) new JSONParser().parse(r);
    }
  }

  private static double settingsNum(String key) throws Exception {
    JSONObject root = readJson(Paths.get(PP_DIR, "settings.json"));
    return ((Number) root.get(key)).doubleValue();
  }

  /**
   * settings.json stores robotFeatures as an array of JSON-encoded *strings*, so each
   * entry has to be parsed before it can be re-emitted as a real object.
   */
  private static String robotFeatures() throws Exception {
    JSONObject root = readJson(Paths.get(PP_DIR, "settings.json"));
    Object rf = root.get("robotFeatures");
    List<String> out = new ArrayList<>();
    if (rf instanceof JSONArray) {
      for (Object entry : (JSONArray) rf) {
        try {
          JSONObject f = (JSONObject) new JSONParser().parse(String.valueOf(entry));
          out.add(f.toJSONString());
        } catch (Exception e) {
          System.out.println("  skipping unparseable robotFeature: " + entry);
        }
      }
    }
    return "[" + String.join(", ", out) + "]";
  }

  private static String q(String s) {
    return "\"" + String.valueOf(s).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String qList(List<String> xs) {
    List<String> quoted = new ArrayList<>();
    for (String x : xs) quoted.add(q(x));
    return "[" + String.join(", ", quoted) + "]";
  }

  private static double r3(double d) {
    return Math.round(d * 1000.0) / 1000.0;
  }
}
