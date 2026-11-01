import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

/** Streaming aggregation keeps memory independent of the event count. */
public final class JfrWindowSummary {
    private static final Set<String> TYPES = Set.of("ysm.PageSpan", "jdk.FileRead", "jdk.FileWrite",
        "jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.GCPhasePause", "jdk.ExecutionSample",
        "jdk.CPULoad", "jdk.ThreadCPULoad");
    private record Window(int visit, long start, long end, Map<String, Group> groups) {}
    private static final class Group {
        long count, nanos, bytes;
        List<String> cpu = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
    }
    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static double number(RecordedObject object, String field) {
        return object.hasField(field) ? ((Number)object.getValue(field)).doubleValue() : -1;
    }
    public static void main(String[] arguments) throws Exception {
        var windows = new ArrayList<Window>();
        for (String line : Files.readAllLines(Path.of(arguments[1]))) {
            var fields = line.split("\t");
            windows.add(new Window(Integer.parseInt(fields[0]), Long.parseLong(fields[1]),
                Long.parseLong(fields[2]), new LinkedHashMap<>()));
        }
        var totals = new TreeMap<String, Long>();
        try (var recording = new RecordingFile(Path.of(arguments[0]))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                var type = event.getEventType().getName();
                totals.merge(type, 1L, Long::sum);
                if (!TYPES.contains(type)) continue;
                long start = nanos(event.getStartTime());
                long duration = event.getDuration().toNanos();
                RecordedThread thread = event.hasField("eventThread") ? event.getValue("eventThread")
                    : event.hasField("sampledThread") ? event.getValue("sampledThread") : null;
                String key = type + ":" + (thread == null ? "none" : thread.getJavaName());
                if (event.hasField("cardTask") && Boolean.TRUE.equals(event.getValue("cardTask"))) key += ":card-task";
                if (event.hasField("operation")) key += ":" + event.getString("operation");
                for (var window : windows) {
                    long overlap = Math.max(0, Math.min(window.end(), start + duration) - Math.max(window.start(), start));
                    if (overlap == 0 && !(duration == 0 && window.start() <= start && start < window.end())) continue;
                    var group = window.groups().computeIfAbsent(key, ignored -> new Group());
                    group.count++; group.nanos += overlap;
                    if (event.hasField("bytesRead")) group.bytes += event.getLong("bytesRead");
                    if (event.hasField("bytesWritten")) group.bytes += event.getLong("bytesWritten");
                    if (type.equals("jdk.CPULoad")) group.cpu.add(number(event,"jvmUser") + "," + number(event,"jvmSystem") + "," + number(event,"machineTotal"));
                    if (type.equals("jdk.ThreadCPULoad")) group.cpu.add(number(event,"user") + "," + number(event,"system") + ",-1");
                    if ((type.equals("jdk.ThreadPark") || type.equals("jdk.JavaMonitorEnter")
                        || type.equals("jdk.FileRead") || type.equals("jdk.FileWrite")) && group.paths.size() < 8) {
                        var trace = event.getStackTrace();
                        if (trace != null) {
                            var names = new ArrayList<String>();
                            for (var frame : trace.getFrames().subList(0, Math.min(8, trace.getFrames().size())))
                                names.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
                            group.paths.add(String.join(" <- ", names));
                        }
                    }
                }
            }
        }
        try (var writer = Files.newBufferedWriter(Path.of(arguments[2]))) {
            for (var window : windows) for (var entry : window.groups().entrySet()) {
                var group = entry.getValue();
                writer.write(window.visit() + "\t" + encode(entry.getKey()) + "\t" + group.count + "\t" + group.nanos + "\t" + group.bytes
                    + "\t" + encode(String.join(";", group.cpu)) + "\t" + encode(String.join("\n", group.paths)) + "\n");
            }
        }
        try (var writer = Files.newBufferedWriter(Path.of(arguments[2] + ".types.tsv"))) {
            for (var entry : totals.entrySet()) writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
        }
    }
}
