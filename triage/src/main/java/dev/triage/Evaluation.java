package dev.triage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class Evaluation {
    record Score(String example, String input, String engine, Reports.Cause expected,
                 Reports.Cause actual, boolean correct, boolean requiredSignalsCited,
                 Reports.Diagnosis report, String error) {}

    static void run(Path output, String model) throws Exception {
        var results = new ArrayList<Score>();
        for (var scenario : Signals.Scenario.values()) {
            Path dir = output.resolve(scenario.name().toLowerCase(Locale.ROOT));
            if (!Files.exists(dir.resolve("label.txt"))) Experiment.run(scenario, "virtual", dir, 40);
            if (!Files.readString(dir.resolve("label.txt")).strip().equals(scenario.name()))
                throw new IllegalArgumentException("Unexpected fixture label: " + dir);
            var jfr = dir.resolve(Analysis.JFR);
            var metrics = dir.resolve(Analysis.METRICS);
            var expected = switch (scenario) {
                case HEALTHY -> Reports.Cause.HEALTHY;
                case SLOW -> Reports.Cause.SLOW_DEPENDENCY;
                case POOL -> Reports.Cause.CONNECTION_POOL;
                case LOCK -> Reports.Cause.LOCK_CONTENTION;
            };
            score(Analysis.read(jfr, metrics), scenario.name(), "full", expected, model, results);
            String key = switch (scenario) {
                case HEALTHY -> "request_p99_ms";
                case SLOW -> "downstream_p95_ms";
                case POOL -> "pool_busy_max";
                case LOCK -> "lock_waiters_max";
            };
            Path reduced = dir.resolve("metrics-ablated.csv");
            ablate(metrics, reduced, key);
            score(Analysis.read(jfr, reduced), scenario.name(), "without " + key,
                    Reports.Cause.INSUFFICIENT_EVIDENCE, model, results);
        }
        Files.writeString(output.resolve("evaluation.json"),
                Reports.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results) + "\n");
        for (String engine : results.stream().map(Score::engine).distinct().toList()) {
            var scores = results.stream().filter(s -> s.engine.equals(engine)).toList();
            var full = scores.stream().filter(s -> s.input.equals("full")).toList();
            var ablated = scores.stream().filter(s -> !s.input.equals("full")).toList();
            System.out.printf("%s: full diagnosis %d/%d; required citations %d/%d on full evidence; abstention %d/%d; errors %d%n",
                    engine, full.stream().filter(Score::correct).count(), full.size(),
                    full.stream().filter(Score::requiredSignalsCited).count(), full.size(),
                    ablated.stream().filter(Score::correct).count(), ablated.size(),
                    scores.stream().filter(s -> s.error != null).count());
        }
        System.out.println("Per-case reports: " + output.resolve("evaluation.json"));
        if (results.stream().anyMatch(s -> s.engine.equals("rules") && (!s.correct || !s.requiredSignalsCited))
                || results.stream().anyMatch(s -> s.error != null))
            throw new IllegalStateException("Evaluation found baseline regressions or engine errors; see per-case reports.");
    }

    private static void score(Analysis.Evidence e, String example, String input, Reports.Cause expected,
                              String model, List<Score> results) {
        var baseline = Reports.rules(e);
        results.add(scored(e, example, input, "rules", expected, baseline));
        if (model == null) return;
        try {
            results.add(scored(e, example, input, "ollama:" + model, expected, Reports.ai(e, model)));
        } catch (Exception ex) {
            results.add(new Score(example, input, "ollama:" + model, expected, null, false, false, null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    private static Score scored(Analysis.Evidence e, String example, String input, String engine,
                                Reports.Cause expected, Reports.Diagnosis actual) {
        boolean correct = expected == actual.cause();
        return new Score(example, input, engine, expected, actual.cause(), correct,
                correct && Reports.citesRequiredSignals(e, actual), actual, null);
    }

    private static void ablate(Path source, Path destination, String metric) throws Exception {
        var lines = Files.readAllLines(source);
        int column = List.of(lines.getFirst().split(",", -1)).indexOf(metric);
        if (column < 2) throw new IllegalArgumentException("Missing ablation column " + metric);
        for (int i = 1; i < lines.size(); i++) {
            var cells = lines.get(i).split(",", -1);
            cells[column] = "";
            lines.set(i, String.join(",", cells));
        }
        Files.write(destination, lines);
    }
}
