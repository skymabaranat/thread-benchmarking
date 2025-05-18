package com.example.threadbenchmarkingapp.service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ThreadBenchmarkService {

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    public Map<Integer, Long> normalThreadBenchmark(int threadCount, int workIterations, Map<Integer, Long> memoryUsage) {
        Map<Integer, Long> benchmarkResults = new TreeMap<>();

        for (int i = 1000; i <= threadCount; i += 1000) {
            System.gc();

            MemoryUsage heapBefore = memoryMXBean.getHeapMemoryUsage();
            long memoryBefore = heapBefore.getUsed();

            Instant start = Instant.now();
            List<Thread> threads = new ArrayList<>();

            for (int j = 0; j < i; j++) {
                Thread thread = new Thread(() -> {
                    int sum = 0;
                    for (int k = 0; k < workIterations; k++) {
                        sum += k;
                    }
                });
                threads.add(thread);
                thread.start();
            }

            threads.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });

            Instant end = Instant.now();
            long duration = Duration.between(start, end).toMillis();

            System.gc();
            MemoryUsage heapAfter = memoryMXBean.getHeapMemoryUsage();
            long memoryAfter = heapAfter.getUsed();
            long memoryConsumed = Math.max(memoryAfter - memoryBefore, 0);

            benchmarkResults.put(i, duration);
            memoryUsage.put(i, memoryConsumed);

            System.out.println("Normal Threads (" + i + "): " + duration + "ms, Memory Usage: " + memoryConsumed / 1024 + " KB");
        }

        return benchmarkResults;
    }

    public Map<Integer, Long> virtualThreadBenchmark(int threadCount, int workIterations, Map<Integer, Long> memoryUsage) {
        Map<Integer, Long> benchmarkResults = new TreeMap<>();

        for (int i = 1000; i <= threadCount; i += 1000) {
            System.gc();

            MemoryUsage heapBefore = memoryMXBean.getHeapMemoryUsage();
            long memoryBefore = heapBefore.getUsed();

            Instant start = Instant.now();
            List<Thread> threads = new ArrayList<>();

            for (int j = 0; j < i; j++) {
                Thread thread = Thread.ofVirtual().start(() -> {
                    int sum = 0;
                    for (int k = 0; k < workIterations; k++) {
                        sum += k;
                    }
                });
                threads.add(thread);
            }

            threads.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });

            Instant end = Instant.now();
            long duration = Duration.between(start, end).toMillis();

            System.gc();
            MemoryUsage heapAfter = memoryMXBean.getHeapMemoryUsage();
            long memoryAfter = heapAfter.getUsed();
            long memoryConsumed = Math.max(memoryAfter - memoryBefore, 0);

            benchmarkResults.put(i, duration);
            memoryUsage.put(i, memoryConsumed);

            System.out.println("Virtual Threads (" + i + "): " + duration + "ms, Memory Usage: " + memoryConsumed / 1024 + " KB");
        }

        return benchmarkResults;
    }
}
