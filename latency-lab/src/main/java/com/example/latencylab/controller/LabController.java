package com.example.latencylab.controller;

import com.example.latencylab.service.LatencySimulator;
import com.example.latencylab.service.PercentileCalculator;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LabController {

    private final LatencySimulator simulator;
    private final PercentileCalculator calculator;
    private final Timer requestLatencyTimer;

    public LabController(LatencySimulator simulator,
                         PercentileCalculator calculator,
                         Timer requestLatencyTimer) {
        this.simulator = simulator;
        this.calculator = calculator;
        this.requestLatencyTimer = requestLatencyTimer;
    }

    /**
     * Simulates an API call with variable latency.
     * Each call randomly picks: fast / medium / slow / timeout.
     * The latency is recorded in both our manual calculator AND Micrometer.
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulate() {
        long start = System.nanoTime();
        String scenario = simulator.simulateWorkWithLabel();
        long durationNanos = System.nanoTime() - start;
        long durationMs = durationNanos / 1_000_000;

        // Record in our manual calculator
        calculator.record(durationMs);

        // Record in Micrometer (this feeds Prometheus)
        requestLatencyTimer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);

        return ResponseEntity.ok(Map.of(
                "scenario", scenario,
                "latency_ms", durationMs,
                "message", "Request completed. Check /api/percentiles to see how this affected the distribution."
        ));
    }

    /**
     * Fires N requests at once so you can quickly build up data.
     * Default: 100 requests. Max: 500.
     */
    @PostMapping("/simulate/batch")
    public ResponseEntity<Map<String, Object>> simulateBatch(
            @RequestParam(defaultValue = "100") int count) {
        int actualCount = Math.min(count, 500);
        long batchStart = System.currentTimeMillis();

        for (int i = 0; i < actualCount; i++) {
            long start = System.nanoTime();
            simulator.simulateWork();
            long durationNanos = System.nanoTime() - start;
            long durationMs = durationNanos / 1_000_000;

            calculator.record(durationMs);
            requestLatencyTimer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        long totalMs = System.currentTimeMillis() - batchStart;

        return ResponseEntity.ok(Map.of(
                "requests_fired", actualCount,
                "total_time_ms", totalMs,
                "message", "Batch complete. Check /api/percentiles for results."
        ));
    }

    /**
     * Shows our manually calculated percentiles.
     * This is the "naive" approach so you can see the math.
     */
    @GetMapping("/percentiles")
    public ResponseEntity<Map<String, Object>> getPercentiles() {
        return ResponseEntity.ok(calculator.calculate());
    }

    /**
     * Returns raw sorted latency samples for the dashboard histogram.
     */
    @GetMapping("/samples")
    public ResponseEntity<List<Long>> getSamples() {
        return ResponseEntity.ok(calculator.getSortedSamples());
    }

    /**
     * Resets all collected data. Start fresh.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset() {
        calculator.reset();
        return ResponseEntity.ok(Map.of("message", "All samples cleared. Start fresh."));
    }
}
