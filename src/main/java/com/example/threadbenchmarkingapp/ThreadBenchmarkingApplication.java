package com.example.threadbenchmarkingapp;

import com.example.threadbenchmarkingapp.service.ThreadBenchmarkService;
import org.knowm.xchart.SwingWrapper;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ThreadBenchmarkingApplication {

    private static final int THREAD_COUNT = 10000;
    private static final int WORK_ITERATIONS = 1000;

    public static void main(String[] args) {
        System.out.println("____________________Thread Benchmarking App____________________");

        ThreadBenchmarkService service = new ThreadBenchmarkService();
        Map<Integer, Long> normalMemoryUsage = new TreeMap<>();
        Map<Integer, Long> virtualMemoryUsage = new TreeMap<>();

        System.out.println("\n____________________ Benchmarking Normal Threads ____________________");
        Map<Integer, Long> normalResults = service.normalThreadBenchmark(THREAD_COUNT, WORK_ITERATIONS, normalMemoryUsage);

        System.out.println("\n____________________ Benchmarking Virtual Threads ____________________");
        Map<Integer, Long> virtualResults = service.virtualThreadBenchmark(THREAD_COUNT, WORK_ITERATIONS, virtualMemoryUsage);

        plotResults(normalResults, virtualResults, normalMemoryUsage, virtualMemoryUsage);
        displayTable(normalResults, virtualResults, normalMemoryUsage, virtualMemoryUsage);
    }

    private static void plotResults(Map<Integer, Long> normalResults, Map<Integer, Long> virtualResults,
                                    Map<Integer, Long> normalMemoryUsage, Map<Integer, Long> virtualMemoryUsage) {
        XYChart chart = new XYChartBuilder()
            .width(800)
            .height(600)
            .title("Thread Benchmarking")
            .xAxisTitle("Number of Threads")
            .yAxisTitle("Execution Time (ms)")
            .build();

        chart.addSeries("Normal Threads", new ArrayList<>(normalResults.keySet()), new ArrayList<>(normalResults.values()));
        chart.addSeries("Virtual Threads", new ArrayList<>(virtualResults.keySet()), new ArrayList<>(virtualResults.values()));

        XYChart memoryChart = new XYChartBuilder()
            .width(800)
            .height(600)
            .title("Memory Usage Benchmarking")
            .xAxisTitle("Number of Threads")
            .yAxisTitle("Memory Usage (KB)")
            .build();

        memoryChart.addSeries("Normal Threads Memory", new ArrayList<>(normalMemoryUsage.keySet()), new ArrayList<>(normalMemoryUsage.values()));
        memoryChart.addSeries("Virtual Threads Memory", new ArrayList<>(virtualMemoryUsage.keySet()), new ArrayList<>(virtualMemoryUsage.values()));

        SwingWrapper<XYChart> wrapper = new SwingWrapper<>(List.of(chart, memoryChart));
        wrapper.displayChartMatrix();
    }

    private static void displayTable(Map<Integer, Long> normalResults, Map<Integer, Long> virtualResults,
                                     Map<Integer, Long> normalMemoryUsage, Map<Integer, Long> virtualMemoryUsage) {
        JFrame frame = new JFrame("Benchmark Results");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 400);

        String[] columns = {"Number of Threads", "Normal Thread Time (ms)", "Virtual Thread Time (ms)",
            "Normal Thread Memory (KB)", "Virtual Thread Memory (KB)"};

        DefaultTableModel model = new DefaultTableModel();
        model.setColumnIdentifiers(columns);

        normalResults.forEach((threadCount, normalTime) -> {
            Long virtualTime = virtualResults.get(threadCount);
            Long normalMem = normalMemoryUsage.get(threadCount) / 1024;
            Long virtualMem = virtualMemoryUsage.get(threadCount) / 1024;
            model.addRow(new Object[]{threadCount, normalTime, virtualTime, normalMem, virtualMem});
        });

        JTable table = new JTable(model);
        JScrollPane scrollPane = new JScrollPane(table);
        frame.add(scrollPane, BorderLayout.CENTER);

        frame.setVisible(true);
    }
}
