import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Reduces fixed JFR weights against explicit per-thread expression preparation spans. */
public final class JfrStartupExtract {
    record Span(long thread, Instant start, Instant end, String label) {}
    record Allocation(long thread, Instant time, long weight) {}
    record Work(Instant time, String label) {}
    record Numeric(long thread, Instant time, String label, String scope, String kind, long value, boolean duration) {}

    public static void main(String[] args) throws Exception {
        var boundaries = new HashMap<String, Instant>();
        var spans = new ArrayList<Span>();
        var allocations = new ArrayList<Allocation>();
        var work = new HashMap<String, Long>();
        var workEvents = new ArrayList<Work>();
        var numericEvents = new ArrayList<Numeric>();
        var durations = new HashMap<String, Long>();
        var analysis = new HashMap<String, Long>();
        String vm = "null";
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                switch (event.getEventType().getName()) {
                    case "jdk.JVMInformation" -> vm = "{\"pid\":" + event.getLong("pid")
                            + ",\"name\":" + quoted(event.getString("jvmName"))
                            + ",\"version\":" + quoted(event.getString("jvmVersion"))
                            + ",\"jvm_arguments\":" + quoted(event.getString("jvmArguments"))
                            + ",\"java_arguments\":" + quoted(event.getString("javaArguments")) + "}";
                    case "ysm.StartupBoundary" -> {
                        if (boundaries.put(event.getString("label"), event.getStartTime()) != null)
                            throw new IllegalStateException("Duplicate startup boundary");
                    }
                    case "ysm.ExpressionPreparation" -> spans.add(new Span(
                            event.getThread().getJavaThreadId(), event.getStartTime(), event.getEndTime(), event.getString("label")));
                    case "jdk.ObjectAllocationSample" -> allocations.add(new Allocation(
                            event.getThread().getJavaThreadId(), event.getStartTime(), event.getLong("weight")));
                    case "ysm.ExpressionWork" -> workEvents.add(new Work(event.getStartTime(), event.getString("label")));
                    case "ysm.ExpressionDuration", "ysm.AnalysisWork" -> {
                        boolean duration = event.getEventType().getName().equals("ysm.ExpressionDuration");
                        long value = event.getLong(duration ? "nanoseconds" : "delta");
                        if (value < 0) throw new IllegalStateException("Negative numeric observation");
                        numericEvents.add(new Numeric(event.getThread().getJavaThreadId(), event.getStartTime(),
                                event.getString(duration ? "label" : "counter"), event.getString("scope"),
                                event.getString("compilationKind"), value, duration));
                    }
                    default -> { }
                }
            }
        }
        for (var name : Set.of("COMMON_SETUP", "REQUIRED_READY", "INVENTORY_COMPLETE", "NORMAL_STOP_REQUESTED"))
            if (!boundaries.containsKey(name)) throw new IllegalStateException("Missing boundary: " + name);
        var start = boundaries.get("COMMON_SETUP");
        var end = boundaries.get("REQUIRED_READY");
        if (!end.isAfter(start) || spans.isEmpty()) throw new IllegalStateException("Invalid startup window");
        workEvents.stream().filter(event -> !event.time().isBefore(start) && event.time().isBefore(end))
                .forEach(event -> work.merge(event.label(), 1L, Long::sum));
        var observed = numericEvents.stream().filter(event -> !event.time().isBefore(start) && event.time().isBefore(end)).toList();
        for (var event : observed) {
            if (event.scope() != null && spans.stream().noneMatch(span -> span.thread() == event.thread()
                    && span.label().equals(event.scope()) && !event.time().isBefore(span.start())
                    && !event.time().isAfter(span.end()))) {
                throw new IllegalStateException("Numeric event has no matching preparation thread/scope");
            }
            if (event.duration()) {
                durations.merge(event.label(), event.value(), Math::addExact);
                work.merge(event.label(), 1L, Math::addExact);
            } else analysis.merge(event.kind() + "." + event.label(), event.value(), Math::addExact);
        }
        long bytes = 0, samples = 0;
        for (var sample : allocations) {
            if (sample.time().isBefore(start) || !sample.time().isBefore(end)) continue;
            boolean included = spans.stream().anyMatch(span -> span.thread() == sample.thread()
                    && !sample.time().isBefore(span.start()) && sample.time().isBefore(span.end()));
            if (included) { bytes = Math.addExact(bytes, sample.weight()); samples++; }
        }
        for (var span : spans)
            if (span.start().isBefore(start) || span.end().isAfter(end))
                throw new IllegalStateException("Expression span outside startup window");
        System.out.println("{\"window_nanos\":" + java.time.Duration.between(start, end).toNanos()
                + ",\"expression_allocation_weight\":" + bytes + ",\"allocation_samples\":" + samples
                + ",\"expression_spans\":" + spans.size() + ",\"work\":" + map(work)
                + ",\"stage_nanos\":" + map(durations) + ",\"analysis_work\":" + map(analysis)
                + ",\"preparation_spans\":" + spans.stream().map(span -> "{\"thread\":" + span.thread()
                        + ",\"scope\":" + quoted(span.label()) + ",\"start\":" + quoted(span.start().toString())
                        + ",\"end\":" + quoted(span.end().toString()) + "}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                + ",\"numeric_events\":" + observed.stream().map(event -> "{\"thread\":" + event.thread()
                        + ",\"scope\":" + quoted(event.scope()) + ",\"kind\":" + quoted(event.kind())
                        + ",\"label\":" + quoted(event.label()) + ",\"value\":" + event.value()
                        + ",\"duration\":" + event.duration() + "}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                + ",\"jvm\":" + vm + "}");
    }

    private static String map(Map<String, Long> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
