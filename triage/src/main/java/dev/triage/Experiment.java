package dev.triage;

import com.sun.net.httpserver.HttpServer;
import jdk.jfr.Recording;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

final class Experiment {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(LabService.class)
    static class Config {}

    static void run(Signals.Scenario scenario, String mode, Path output, int rate) throws Exception {
        Main.require(mode.equals("platform") || mode.equals("virtual"));
        Files.createDirectories(output);
        for (String name : new String[] {"recording.jfr", "metrics.csv", "label.txt"}) {
            if (Files.exists(output.resolve(name))) throw new IllegalArgumentException("Capture already exists: " + output);
        }
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        try (var stubs = Executors.newVirtualThreadPerTaskExecutor()) {
            stub.setExecutor(stubs);
            stub.createContext("/dependency", exchange -> {
                try (exchange) {
                    var query = exchange.getRequestURI().getRawQuery();
                    Thread.sleep("delay=320".equals(query) ? 320 : 20);
                    exchange.sendResponseHeaders(200, -1);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            stub.start();
            var spring = new SpringApplication(Config.class);
            spring.setDefaultProperties(Map.of(
                    "server.port", "0", "server.address", "127.0.0.1",
                    "spring.threads.virtual.enabled", "" + mode.equals("virtual"),
                    "server.tomcat.threads.max", "128", "spring.main.banner-mode", "off",
                    "logging.level.root", "WARN", "spring.jmx.enabled", "false"));
            try (var app = (ServletWebServerApplicationContext) spring.run();
                 var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                         .version(HttpClient.Version.HTTP_1_1).build();
                 var recording = new Recording()) {
                var target = URI.create("http://127.0.0.1:" + app.getWebServer().getPort());
                var dependency = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
                var service = app.getBean(LabService.class);
                service.configure(dependency, new Signals.Run(scenario, mode, rate));
                warmup(client, target, mode);
                recording.enable(Signals.Phase.class).withStackTrace().withThreshold(Duration.ZERO);
                for (String event : Analysis.JVM_EVENTS) {
                    if (event.equals("jdk.CPULoad")) recording.enable(event).withPeriod(Duration.ofSeconds(1));
                    else recording.enable(event).withStackTrace().withThreshold(Duration.ofMillis(2));
                }
                recording.start();
                var signals = new Signals.Run(scenario, mode, rate);
                service.configure(dependency, signals);
                load(client, target, service, signals);
                service.drain();
                recording.stop();
                var actual = signals.requests.stream().map(Signals.Request::mode).filter(s -> !s.isEmpty()).distinct().toList();
                if (!actual.equals(java.util.List.of(mode))) throw new IllegalStateException("Unexpected handler modes: " + actual);
                recording.dump(output.resolve("recording.jfr"));
                Files.write(output.resolve("metrics.csv"), signals.csv());
                Files.writeString(output.resolve("label.txt"), scenario.name() + "\n");
                System.out.printf("%s %s: %d requests at %d offered req/s. Saved %s%n",
                        scenario, mode, signals.requests.size(), rate, output);
            } finally { stub.stop(0); }
        }
    }

    private static void warmup(HttpClient client, URI target, String mode) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var checks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) {
                long id = -1L - i;
                checks.add(executor.submit(() -> {
                    var response = client.send(request(target, id), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() != 200 || !response.headers().firstValue("X-Handler-Mode").orElse("").equals(mode))
                        throw new IllegalStateException("Warm-up failed or handler thread mode mismatch");
                    return null;
                }));
                if (i % 4 == 3) {
                    for (var check : checks) check.get();
                    checks.clear();
                }
            }
        }
    }

    private static HttpRequest request(URI target, long id) {
        return HttpRequest.newBuilder(target.resolve("/work/" + id)).timeout(Duration.ofSeconds(3)).build();
    }

    private static void load(HttpClient client, URI target, LabService service, Signals.Run run)
            throws InterruptedException {
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> run.samples.add(service.sample()), 0, 25, TimeUnit.MILLISECONDS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int total = Math.toIntExact(Signals.WINDOWS * Signals.WINDOW_MS * run.rate / 1000);
            for (long id = 0; id < total; id++) {
                long offset = id * 1_000_000_000L / run.rate;
                long due = run.startNano + offset;
                while (System.nanoTime() < due) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    LockSupport.parkNanos(due - System.nanoTime());
                }
                long requestId = id;
                executor.execute(() -> {
                    long start = System.nanoTime();
                    int status = -1;
                    String threadMode = "";
                    try {
                        var response = client.send(request(target, requestId), HttpResponse.BodyHandlers.discarding());
                        status = response.statusCode();
                        threadMode = response.headers().firstValue("X-Handler-Mode").orElse("");
                    } catch (java.net.http.HttpTimeoutException e) { status = 0; }
                    catch (IOException e) { /* transport error */ }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally {
                        run.requests.add(new Signals.Request(offset / 1_000_000, status,
                                (System.nanoTime() - due) / 1e6, (start - due) / 1e6, threadMode));
                    }
                });
            }
        } finally { sampler.shutdownNow(); }
    }
}
