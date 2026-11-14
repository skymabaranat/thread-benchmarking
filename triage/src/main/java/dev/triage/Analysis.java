package dev.triage;

import jdk.jfr.consumer.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

final class Analysis {
    static final Set<String> JVM_EVENTS = Set.of("jdk.ThreadPark", "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait", "jdk.SocketRead", "jdk.VirtualThreadPinned", "jdk.CPULoad");
    static final String METRICS = "metrics.csv";
    static final String JFR = "recording.jfr";

    record Fact(String id, String source, String window, String signal, String description) {}
    record Stack(String event, String phase, String thread, String stack, long count, double totalMs, double maxMs) {}
    record Window(String id, String start, String end, Map<String, String> metrics, Double jvmCpuPercent,
                  Map<String, Double> phaseP95Ms, Map<String, Long> phaseCounts, List<Stack> waits) {}
    record Evidence(List<Window> windows, List<Fact> facts, boolean hasMetrics, String attribution) {}

    private record Key(String event, String phase, String thread, String stack) {}
    private static final class Bucket {
        final Instant start;
        final Instant end;
        final Map<String, String> metrics;
        final Map<Key, Tally> stacks = new HashMap<>();
        final Map<String, List<Double>> phases = new TreeMap<>();
        double cpuTotal;
        int cpuSamples;

        Bucket(Instant start, Instant end, Map<String, String> metrics) {
            this.start = start;
            this.end = end;
            this.metrics = metrics;
        }
    }

    private static final class Tally {
        long count;
        double sum, max;
        void add(double ms) { count++; sum += ms; max = Math.max(max, ms); }
    }

    static Evidence read(Path jfr, Path csv) throws Exception {
        List<Bucket> buckets = csv == null ? new ArrayList<>() : metrics(csv);
        boolean hasMetrics = !buckets.isEmpty();
        boolean labMetrics = hasMetrics && buckets.stream().anyMatch(b -> b.metrics.containsKey("handler_mode"));
        SortedMap<Instant, Bucket> synthetic = new TreeMap<>();
        try (var recording = new RecordingFile(jfr)) {
            boolean labEvents = recording.readEventTypes().stream().anyMatch(e -> e.getName().equals("triage.Phase"));
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!JVM_EVENTS.contains(type) && !type.equals("triage.Phase")) continue;
                Instant when = event.getStartTime();
                Bucket bucket;
                if (hasMetrics) {
                    bucket = bucketAt(buckets, when);
                    if (bucket == null) continue;
                } else {
                    Instant start = Instant.ofEpochMilli(Math.floorDiv(when.toEpochMilli(), 2000) * 2000);
                    bucket = synthetic.computeIfAbsent(start, s -> new Bucket(s, s.plusMillis(2000), Map.of()));
                }
                if (type.equals("jdk.CPULoad")) {
                    bucket.cpuTotal += event.getFloat("jvmUser") + event.getFloat("jvmSystem");
                    bucket.cpuSamples++;
                    continue;
                }
                double ms = event.getDuration().toNanos() / 1e6;
                var stack = event.getStackTrace();
                String phase = type.equals("triage.Phase") ? event.getString("phase") : "";
                if (!phase.isEmpty()) bucket.phases.computeIfAbsent(phase, p -> new ArrayList<>()).add(ms);
                if (phase.equals("handler") || ms < 2) continue;
                String thread = event.getThread() == null ? "unknown" : event.getThread().isVirtual() ? "virtual" : "platform";
                if (phase.isEmpty() && stack != null) {
                    boolean app = stack.getFrames().stream().map(Analysis::frame).anyMatch(f -> f.startsWith("dev.triage.LabService."));
                    if (!app && (labMetrics || labEvents)) continue;
                }
                String frames = stack == null ? "(no stack captured)" : stack.getFrames().stream()
                        .limit(12).map(Analysis::frame).collect(Collectors.joining(" ← "));
                var key = new Key(type, phase, thread, frames);
                bucket.stacks.computeIfAbsent(key, k -> new Tally()).add(ms);
            }
        }
        if (!hasMetrics) buckets.addAll(synthetic.values());
        List<Window> windows = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        for (var b : buckets) {
            String id = "W" + (windows.size() + 1);
            var p95 = new TreeMap<String, Double>();
            var counts = new TreeMap<String, Long>();
            b.phases.forEach((phase, values) -> {
                values.sort(Double::compare);
                p95.put(phase, round(values.get((int) Math.ceil(values.size() * .95) - 1)));
                counts.put(phase, (long) values.size());
            });
            var waits = b.stacks.entrySet().stream()
                    .map(e -> new Stack(e.getKey().event, e.getKey().phase, e.getKey().thread,
                            e.getKey().stack, e.getValue().count, round(e.getValue().sum), round(e.getValue().max)))
                    .sorted(Comparator.comparingDouble(Stack::totalMs).reversed()
                            .thenComparing(Stack::event).thenComparing(Stack::phase).thenComparing(Stack::thread)
                            .thenComparing(Stack::stack)).limit(8).toList();
            var w = new Window(id, b.start.toString(), b.end.toString(), b.metrics,
                    b.cpuSamples == 0 ? null : round(100 * b.cpuTotal / b.cpuSamples), p95, counts, waits);
            windows.add(w);
            addFacts(w, facts);
        }
        return new Evidence(windows, facts, hasMetrics,
                "JFR events grouped by UTC start window and stack. Waits can nest or overlap across threads; do not add them to get request latency.");
    }

    private static Bucket bucketAt(List<Bucket> buckets, Instant when) {
        int low = 0, high = buckets.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            var b = buckets.get(middle);
            if (when.isBefore(b.start)) high = middle - 1;
            else if (!when.isBefore(b.end)) low = middle + 1;
            else return b;
        }
        return null;
    }

    private static List<Bucket> metrics(Path csv) throws Exception {
        var lines = Files.readAllLines(csv);
        if (lines.size() < 2) throw new IllegalArgumentException("Metrics requires a header and at least one UTC window");
        var headers = lines.getFirst().split(",", -1);
        if (headers.length < 2 || !headers[0].equals("start_utc") || !headers[1].equals("end_utc")
                || Set.copyOf(List.of(headers)).size() != headers.length)
            throw new IllegalArgumentException("Metrics requires unique headers beginning start_utc,end_utc");
        List<Bucket> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            var row = lines.get(i).split(",", -1);
            if (row.length != headers.length) throw new IllegalArgumentException("Bad metrics row " + (i + 1));
            var start = Instant.parse(row[0]);
            var end = Instant.parse(row[1]);
            if (!end.isAfter(start) || !result.isEmpty() && start.isBefore(result.getLast().end))
                throw new IllegalArgumentException("Metrics windows must be sorted and non-overlapping");
            var metrics = new LinkedHashMap<String, String>();
            for (int j = 2; j < headers.length; j++) {
                if (row[j].isEmpty()) continue;
                if (!headers[j].equals("handler_mode")) {
                    double value = Double.parseDouble(row[j]);
                    if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid metric " + headers[j]);
                }
                metrics.put(headers[j], row[j]);
            }
            result.add(new Bucket(start, end, Collections.unmodifiableMap(metrics)));
        }
        return result;
    }

    private static String frame(RecordedFrame f) { return f.getMethod().getType().getName().replaceFirst("\\.0x[0-9a-fA-F]+$", "") + "." + f.getMethod().getName(); }
    private static double round(double n) { return Math.round(n * 100) / 100.0; }
    static double value(Window w, String key) {
        String v = w.metrics.get(key);
        return v == null ? Double.NaN : Double.parseDouble(v);
    }
    static double phase(Window w, String key) { return w.phaseP95Ms.getOrDefault(key, Double.NaN); }

    private static void addFacts(Window w, List<Fact> facts) {
        metric(w, facts, "request_p99_ms", "request p99 %s ms; %s errors (%s timeouts) / %s offered; %s successful req/s; driver p99 %s ms",
                "request_p99_ms", "errors", "timeouts", "offered", "success_rps", "driver_p99_ms");
        metric(w, facts, "worker_active_max", "workers peak %s/%s; queue peak %s; full in %s/%s samples",
                "worker_active_max", "worker_capacity", "worker_queue_max", "worker_full_samples", "samples");
        metric(w, facts, "downstream_p95_ms", "downstream HTTP p95 %s ms across %s calls",
                "downstream_p95_ms", "downstream_calls");
        metric(w, facts, "pool_busy_max", "connection permits peak %s/%s busy; peak concurrent acquirers %s",
                "pool_busy_max", "pool_capacity", "pool_waiters_max");
        metric(w, facts, "lock_waiters_max", "critical section peak queued callers %s", "lock_waiters_max");
        if (w.jvmCpuPercent != null) fact(w, facts, JFR, "cpu", "mean JVM CPU %.2f%% of host capacity".formatted(w.jvmCpuPercent));
        if (w.phaseP95Ms.isEmpty()) {
            int i = 0;
            for (var stack : w.waits.stream().limit(2).toList()) {
                fact(w, facts, JFR, "wait_" + ++i, "%s (%s): %d events, %.2f ms total; %s".formatted(
                        stack.event, stack.thread, stack.count, stack.totalMs, stack.stack));
            }
        }
        w.phaseP95Ms.forEach((p, ms) -> {
            var stack = w.waits.stream().filter(s -> s.phase.equals(p)).findFirst();
            String site = stack.map(s -> Arrays.stream(s.stack.split(" ← "))
                    .filter(f -> f.startsWith("dev.triage.LabService.")).findFirst().orElse(s.stack))
                    .orElse("");
            fact(w, facts, JFR, p, "%s p95 %.2f ms across %d events%s".formatted(
                    p, ms, w.phaseCounts.get(p), site.isEmpty() ? "" : "; stack includes " + site));
        });
    }

    private static void metric(Window w, List<Fact> facts, String key, String format, String... cols) {
        if (w.metrics.containsKey(key))
            fact(w, facts, METRICS, key, format.formatted(Arrays.stream(cols)
                    .map(c -> w.metrics.getOrDefault(c, "missing")).toArray()));
    }

    private static void fact(Window w, List<Fact> facts, String source, String signal, String desc) {
        facts.add(new Fact(w.id + ":" + signal, source, w.id, signal, desc));
    }
}
