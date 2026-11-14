package dev.triage;

import java.nio.file.Path;
import java.util.Locale;

public final class Main {
    static final String USAGE = """
        ./triage reproduce slow|pool|lock|healthy platform|virtual|both OUT [RPS]
        ./triage explain CAPTURE_DIR [--json | --ai MODEL]
        ./triage analyze RECORDING.jfr METRICS.csv|- [--json | --ai MODEL]
        ./triage evaluate OUT [MODEL]

        'both' writes OUT/platform and OUT/virtual. '-' analyzes JFR without metrics.
        AI uses the local Ollama server on 127.0.0.1:11434.
        """;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        try {
            if (args.length == 0 || args[0].equals("--help")) {
                System.out.print(USAGE);
                return;
            }
            switch (args[0]) {
                case "reproduce" -> {
                    require(args.length == 4 || args.length == 5);
                    var scenario = Signals.Scenario.valueOf(args[1].toUpperCase(Locale.ROOT));
                    var out = Path.of(args[3]);
                    int rate = args.length == 5 ? Integer.parseInt(args[4]) : 40;
                    if (rate < 1 || rate > 100) throw new IllegalArgumentException("RPS must be 1–100");
                    if (args[2].equals("both")) {
                        Experiment.run(scenario, "platform", out.resolve("platform"), rate);
                        Experiment.run(scenario, "virtual", out.resolve("virtual"), rate);
                    } else Experiment.run(scenario, args[2], out, rate);
                }
                case "explain" -> {
                    require(args.length >= 2);
                    var dir = Path.of(args[1]);
                    analyze(dir.resolve("recording.jfr"), dir.resolve("metrics.csv"), args, 2);
                }
                case "analyze" -> {
                    require(args.length >= 3);
                    analyze(Path.of(args[1]), args[2].equals("-") ? null : Path.of(args[2]), args, 3);
                }
                case "evaluate" -> {
                    require(args.length == 2 || args.length == 3);
                    Evaluation.run(Path.of(args[1]), args.length == 3 ? args[2] : null);
                }
                default -> require(false);
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException(USAGE);
    }

    static void analyze(Path jfr, Path metrics, String[] args, int offset) throws Exception {
        int remaining = args.length - offset;
        require(remaining == 0 || remaining == 1 && args[offset].equals("--json")
                || remaining == 2 && args[offset].equals("--ai") && !args[offset + 1].isBlank());
        var evidence = Analysis.read(jfr, metrics);
        if (remaining == 1) {
            System.out.println(Reports.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                    java.util.Map.of("evidence", evidence, "rules", Reports.rules(evidence))));
        } else {
            var diagnosis = remaining == 2 ? Reports.ai(evidence, args[offset + 1]) : Reports.rules(evidence);
            if (remaining == 2 && diagnosis.cause() != Reports.Cause.INSUFFICIENT_EVIDENCE
                    && !Reports.citesRequiredSignals(evidence, diagnosis)) {
                System.err.println("AI diagnosis lacks required supporting evidence. Showing the rules report.");
                diagnosis = Reports.rules(evidence);
            }
            System.out.print(Reports.render(evidence, diagnosis));
        }
    }
}
