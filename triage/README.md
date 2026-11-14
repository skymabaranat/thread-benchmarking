# Java concurrency triage

Use this Spring Boot lab and Java CLI to answer where requests are waiting when CPU is low and requests are timing out. The lab runs the same HTTP workload with platform and virtual threads and saves a Java Flight Recorder (JFR) recording and service metrics for each run.

## Reproduce and explain

From the repository root, use JDK 21 or newer and Maven 3.6.3 or newer. The launcher builds on first use. After editing Java code, run `mvn -f triage/pom.xml package` to rebuild before using the launcher.

```sh
cd triage
./triage reproduce slow both captures/slow
./triage explain captures/slow/virtual
```

The first command runs each thread mode at 40 offered requests per second, with four downstream workers and a queue of 12. After warm-up, each run records four seconds of baseline, six seconds of fault injection, and two seconds of recovery. The second command reads `recording.jfr` and `metrics.csv` and prints a likely cause, the evidence by time window, and a check to test the diagnosis. Use a fresh output path for each new recording.

The slow dependency case should show four busy workers, growing queue depth, HTTP calls taking about 320 ms, and handler stacks waiting in `LabService.throughWorker`. The worker wait budget is 380 ms (HTTP 504). A full queue rejects new tasks with HTTP 503. Calls already running keep their worker until they complete.

Run the fast dependency control and compare both runs at the same offered rate:

```sh
./triage reproduce healthy both captures/control
./triage explain captures/control/virtual
```

| Scenario | What the lab changes | What to look for |
| --- | --- | --- |
| `slow` | Stub HTTP delay rises from 20 to 320 ms | Longer HTTP calls, saturated workers, queue growth |
| `pool` | Requests hold one of three simulated connection permits for an extra 170 ms | Busy permits, longer acquire and hold times, fast downstream HTTP |
| `lock` | A fair Java lock is held for an extra 140 ms | More queued callers and longer lock acquisition |
| `healthy` | The downstream delay stays at 20 ms | Low latency, no errors, and idle worker capacity |

Use `platform` or `virtual` instead of `both` to run one mode. An optional final argument sets the offered requests per second (1–100). The fixtures use 40. The connection pool scenario uses a semaphore to model permit acquisition and hold time.

## Triage a saved recording

1. Pick a UTC interval covering the spike and, if available, a quiet baseline. Collect request p95/p99, arrival rate, errors and timeouts, plus downstream latency, worker occupancy and queue depth, or pool occupancy and acquisition time. Match the recording and metrics to the same instance and interval.
2. If you need a new JFR recording, identify the JVM with `jcmd -l`. On the JVM host and under its service account, record while the symptom is active. Replace the PID and output path with values for that JVM. The recording is written by the target process. [JDK `jcmd` reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html#commands-for-jcmd)

   ```sh
   jcmd 12345 JFR.start name=triage settings=profile duration=60s filename=/absolute/path/incident.jfr
   ```

3. Export metrics as an unquoted CSV. Use the header from a generated `metrics.csv` as a template. Start each row with `start_utc,end_utc` in ISO 8601 and keep windows sorted and non-overlapping. Leave unavailable values blank. Leave the lab-specific `handler_mode` column blank for external captures. Run the analyser from `triage/`:

   ```sh
   ./triage analyze /path/to/incident.jfr /path/to/metrics.csv
   ./triage analyze /path/to/incident.jfr /path/to/metrics.csv --json
   ```

   Use `-` in place of the CSV path to inspect JFR stacks alone. The CLI groups waits by event type, stack, thread mode, and UTC start window. JSON includes all analysed windows. The cause rules use the lab’s custom JFR phases and thresholds. An ordinary JFR exposes supported wait stacks for manual investigation.

4. Compare stacks and metrics in the same window. If HTTP calls got slower while workers filled, compare dependency latency and queue depth with the healthy interval. If waits cluster around connection acquisition, compare connection hold time to query time and check for long transactions. For lock waits, identify the holder and measure time in the critical section. In a controlled rerun, change the suspected cause while holding the arrival rate and limits fixed, then check whether the symptom recedes.

Wait durations can overlap across requests and nest on the same request. Adding the worker wait to the downstream HTTP duration can count the same time twice. In the lab, request latency includes load-driver delay and failed responses. Success rate assigns each success to its scheduled arrival window. Use `driver_p99_ms` to check whether the load driver itself fell behind. The lab’s healthy rule requires driver p99 below 30 ms in every window. If a control run reports insufficient evidence and exceeds that threshold, repeat it with less competing load on the machine.

## Evaluate the optional model

```sh
./triage evaluate captures/evaluation
./triage evaluate captures/evaluation qwen2.5:7b
./triage explain captures/slow/virtual --ai qwen2.5:7b
```

For the optional model commands, run Ollama on loopback and install the named model. The model receives structured observations and evidence IDs. If its selected cause lacks the required citations, `explain --ai` shows the rules report. Evaluation scores the original model responses and writes `evaluation.json`.

In the reference lab run, the rules diagnosed all four full captures and reported insufficient evidence for all four cases with a key metric removed. Qwen 2.5 7B diagnosed three of four full captures correctly, met the citation requirements on zero of four, and abstained on zero of four reduced captures. The rules are the default.
