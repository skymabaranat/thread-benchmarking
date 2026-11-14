package dev.triage;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.ToDoubleFunction;

final class Signals {
    enum Scenario { HEALTHY, SLOW, POOL, LOCK }

    static final long WINDOW_MS = 2_000;
    static final int WINDOWS = 6;
    static final String CSV_HEADER =
            "start_utc,end_utc,offered,success,errors,timeouts,success_rps,request_p95_ms,request_p99_ms,driver_p99_ms,"
            + "worker_active_max,worker_capacity,worker_queue_max,worker_full_samples,samples,"
            + "downstream_p95_ms,downstream_calls,pool_busy_max,pool_capacity,pool_waiters_max,lock_waiters_max,handler_mode";

    @Name("triage.Phase")
    @Label("Triage request phase")
    static class Phase extends Event {
        long requestId;
        String phase;
    }

    record Request(long scheduledMs, int status, double latencyMs, double driverMs, String mode) {}
    record Downstream(long startMs, double durationMs) {}
    record Sample(long elapsedMs, int workers, int queue, int poolBusy, int poolWaiters, int lockWaiters) {}

    static final class Run {
        final Scenario scenario;
        final String mode;
        final int rate;
        final long startNano;
        final Instant startTime;
        final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Downstream> downstream = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();

        Run(Scenario scenario, String mode, int rate) {
            this.scenario = scenario;
            this.mode = mode;
            this.rate = rate;
            startTime = Instant.now();
            startNano = System.nanoTime();
        }

        long elapsed() { return (System.nanoTime() - startNano) / 1_000_000; }
        boolean fault() {
            long ms = elapsed();
            return scenario != Scenario.HEALTHY && ms >= 2 * WINDOW_MS && ms < 5 * WINDOW_MS;
        }

        List<String> csv() {
            List<String> rows = new ArrayList<>();
            rows.add(CSV_HEADER);
            for (int i = 0; i < WINDOWS; i++) {
                long start = i * WINDOW_MS, end = start + WINDOW_MS;
                var cohort = requests.stream().filter(r -> r.scheduledMs >= start && r.scheduledMs < end).toList();
                var calls = downstream.stream().filter(d -> d.startMs >= start && d.startMs < end).toList();
                var snapshots = samples.stream().filter(s -> s.elapsedMs >= start && s.elapsedMs < end).toList();
                long success = cohort.stream().filter(r -> r.status == 200).count();
                long timeouts = cohort.stream().filter(r -> r.status == 504 || r.status == 0).count();
                var modes = cohort.stream().map(Request::mode).filter(s -> !s.isEmpty()).distinct().toList();
                rows.add(String.join(",", startTime.plusMillis(start).toString(), startTime.plusMillis(end).toString(),
                        "" + cohort.size(), "" + success, "" + (cohort.size() - success), "" + timeouts,
                        "%.2f".formatted(success * 1000.0 / WINDOW_MS),
                        percentile(cohort, Request::latencyMs, .95), percentile(cohort, Request::latencyMs, .99),
                        percentile(cohort, Request::driverMs, .99),
                        max(snapshots, Sample::workers), "" + LabService.WORKERS, max(snapshots, Sample::queue),
                        "" + snapshots.stream().filter(s -> s.workers == LabService.WORKERS).count(), "" + snapshots.size(),
                        percentile(calls, Downstream::durationMs, .95), "" + calls.size(),
                        max(snapshots, Sample::poolBusy), "" + LabService.SLOTS, max(snapshots, Sample::poolWaiters),
                        max(snapshots, Sample::lockWaiters), modes.size() == 1 ? modes.getFirst() : modes.isEmpty() ? "" : "mixed"));
            }
            return rows;
        }
    }

    private static <T> String percentile(List<T> rows, ToDoubleFunction<T> field, double p) {
        var sorted = rows.stream().mapToDouble(field).sorted().toArray();
        return sorted.length == 0 ? "" : "%.2f".formatted(sorted[(int) Math.ceil(sorted.length * p) - 1]);
    }

    private static String max(List<Sample> snapshots, java.util.function.ToIntFunction<Sample> field) {
        return snapshots.stream().mapToInt(field).max().stream().mapToObj(Integer::toString).findFirst().orElse("");
    }

    static Phase phase(String name, long id) {
        var event = new Phase();
        event.requestId = id;
        event.phase = name;
        event.begin();
        return event;
    }
}
