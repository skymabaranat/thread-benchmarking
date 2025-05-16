package com.example.threadbenchmarkingapp;

import com.example.threadbenchmarkingapp.service.ThreadBenchmarkService;
import org.knowm.xchart.SwingWrapper;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Map;

public class ThreadBenchmarkingApplication {

    private static final int THREAD_COUNT = 10000;
    private static final int WORK_ITERATIONS = 1000;

    public static void main(String[] args) {
        System.out.println("____________________Thread Benchmarking App____________________");

        ThreadBenchmarkService service = new ThreadBenchmarkService();

        System.out.println("\n____________________ Benchmarking Normal Threads ____________________");
        Map<Integer, Long> normalResults = service.normalThreadBenchmark(THREAD_COUNT, WORK_ITERATIONS);

        System.out.println("\n____________________ Benchmarking Virtual Threads ____________________");
        Map<Integer, Long> virtualResults = service.virtualThreadBenchmark(THREAD_COUNT, WORK_ITERATIONS);

        plotResults(normalResults, virtualResults);
        displayTable(normalResults, virtualResults);
    }

    private static void plotResults(Map<Integer, Long> normalResults, Map<Integer, Long> virtualResults) {
        XYChart chart = new XYChartBuilder()
            .width(800)
            .height(600)
            .title("Thread Benchmarking")
            .xAxisTitle("Number of Threads")
            .yAxisTitle("Execution Time (ms)")
            .build();

        chart.addSeries("Normal Threads", new ArrayList<>(normalResults.keySet()), new ArrayList<>(normalResults.values()));
        chart.addSeries("Virtual Threads", new ArrayList<>(virtualResults.keySet()), new ArrayList<>(virtualResults.values()));

        SwingWrapper<XYChart> wrapper = new SwingWrapper<>(chart);
        wrapper.displayChart();
    }

    private static void displayTable(Map<Integer, Long> normalResults, Map<Integer, Long> virtualResults) {
        JFrame frame = new JFrame("Benchmark Results");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);

        String[] columns = {"Number of Threads", "Normal Thread Time (ms)", "Virtual Thread Time (ms)"};

        DefaultTableModel model = new DefaultTableModel();
        model.setColumnIdentifiers(columns);

        normalResults.forEach((threadCount, normalTime) -> {
            Long virtualTime = virtualResults.get(threadCount);
            model.addRow(new Object[]{threadCount, normalTime, virtualTime});
        });

        JTable table = new JTable(model);
        JScrollPane scrollPane = new JScrollPane(table);
        frame.add(scrollPane, BorderLayout.CENTER);

        frame.setVisible(true);
    }
}
