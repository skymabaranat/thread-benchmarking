package dev.triage;

import jakarta.annotation.PreDestroy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@RestController
final class LabService {
    static final int WORKERS = 4, QUEUE = 12, SLOTS = 3, DEADLINE_MS = 380;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE), Thread.ofPlatform().name("dependency-worker-", 0).factory());
    private final Semaphore slots = new Semaphore(SLOTS, true);
    private final AtomicInteger poolWaiters = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock(true);
    private final HttpClient downstream = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();
    private URI stub;
    private volatile Signals.Run run;

    void configure(URI stub, Signals.Run run) {
        this.stub = stub;
        this.run = run;
    }

    @GetMapping("/ready")
    String ready() { return "ready"; }

    @GetMapping("/work/{id}")
    ResponseEntity<String> work(@PathVariable("id") long id) {
        String mode = Thread.currentThread().isVirtual() ? "virtual" : "platform";
        var request = Signals.phase("handler", id);
        int status;
        try {
            boolean fault = run.fault() && id >= 0;
            status = switch (run.scenario) {
                case POOL -> withConnection(id, fault);
                case LOCK -> withLock(id, fault);
                case SLOW, HEALTHY -> throughWorker(id, fault);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = 503;
        } finally { request.commit(); }
        return ResponseEntity.status(status).header("X-Handler-Mode", mode).body("status=" + status);
    }

    private int throughWorker(long id, boolean fault) throws InterruptedException {
        long queuedAt = System.nanoTime();
        var caller = Signals.phase("worker_await", id);
        FutureTask<Integer> task = new FutureTask<>(() -> {
            // This event runs on the dependency worker. Its duration is the actual HTTP call.
            return callDownstream(id, fault ? 320 : 20);
        });
        try {
            workers.execute(task);
            long remaining = TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS) - (System.nanoTime() - queuedAt);
            return task.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            return 503;
        } catch (TimeoutException e) {
            return 504;
        } catch (ExecutionException e) {
            return 502;
        } finally {
            // Remove tasks that have not started; a call already in flight keeps its worker until it finishes.
            if (workers.remove(task)) task.cancel(false);
            caller.commit();
        }
    }

    private int withConnection(long id, boolean fault) throws InterruptedException {
        var acquire = Signals.phase("connection_acquire", id);
        boolean acquired;
        poolWaiters.incrementAndGet();
        try {
            acquired = slots.tryAcquire(DEADLINE_MS, TimeUnit.MILLISECONDS);
        } finally {
            poolWaiters.decrementAndGet();
            acquire.commit();
        }
        if (!acquired) return 504;
        var hold = Signals.phase("connection_hold", id);
        try {
            if (fault) Thread.sleep(170);
            return callDownstream(id, 20);
        } finally {
            hold.commit();
            slots.release();
        }
    }

    private int withLock(long id, boolean fault) throws InterruptedException {
        var acquire = Signals.phase("lock_acquire", id);
        boolean acquired;
        try { acquired = lock.tryLock(DEADLINE_MS, TimeUnit.MILLISECONDS); }
        finally { acquire.commit(); }
        if (!acquired) return 504;
        var hold = Signals.phase("lock_hold", id);
        try {
            if (fault) Thread.sleep(140);
        } finally {
            hold.commit();
            lock.unlock();
        }
        return callDownstream(id, 20);
    }

    private int callDownstream(long id, int delayMs) throws InterruptedException {
        var call = Signals.phase("downstream_http", id);
        long started = run.elapsed(), nano = System.nanoTime();
        try {
            var endpoint = stub.resolve("/dependency?delay=" + delayMs);
            int status = downstream.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? 200 : 502;
        } catch (IOException e) {
            return 502;
        } finally {
            call.commit();
            if (id >= 0) run.downstream.add(new Signals.Downstream(started, (System.nanoTime() - nano) / 1e6));
        }
    }

    Signals.Sample sample() {
        return new Signals.Sample(run.elapsed(), workers.getActiveCount(), workers.getQueue().size(),
                SLOTS - slots.availablePermits(), poolWaiters.get(), lock.getQueueLength());
    }

    void drain() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("workers did not drain");
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
        downstream.close();
    }
}
