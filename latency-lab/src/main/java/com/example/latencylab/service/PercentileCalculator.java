package com.example.latencylab.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Manual percentile calculator -- so you can see the math yourself.
 *
 * This is the "naive" approach: store all values, sort, pick positions.
 * In production you'd use Micrometer/Prometheus (which we also have).
 * But this makes the concept tangible.
 *
 * Keeps the last N samples in a sliding window (default 1000).
 */
@Service
public class PercentileCalculator {

    private static final int MAX_SAMPLES = 1000;

    private final ConcurrentLinkedDeque<Long> samples = new ConcurrentLinkedDeque<>();

    public void record(long latencyMs) {
        samples.addLast(latencyMs);
        // Evict oldest if over max
        while (samples.size() > MAX_SAMPLES) {
            samples.pollFirst();
        }
    }

    /**
     * Calculate percentiles from the current sample window.
     * Returns a map with p50, p95, p99, min, max, avg, and count.
     */
    public Map<String, Object> calculate() {
        List<Long> snapshot = new ArrayList<>(samples);

        if (snapshot.isEmpty()) {
            return Map.of(
                    "count", 0,
                    "message", "No data yet. Hit /api/simulate a few times first."
            );
        }

        Collections.sort(snapshot);
        int size = snapshot.size();

        long p50 = percentile(snapshot, 50);
        long p90 = percentile(snapshot, 90);
        long p95 = percentile(snapshot, 95);
        long p99 = percentile(snapshot, 99);
        long min = snapshot.get(0);
        long max = snapshot.get(size - 1);
        double avg = snapshot.stream().mapToLong(Long::longValue).average().orElse(0);

        return Map.ofEntries(
                Map.entry("count", size),
                Map.entry("min_ms", min),
                Map.entry("max_ms", max),
                Map.entry("avg_ms", Math.round(avg * 100.0) / 100.0),
                Map.entry("p50_ms", p50),
                Map.entry("p90_ms", p90),
                Map.entry("p95_ms", p95),
                Map.entry("p99_ms", p99),
                Map.entry("note_avg_vs_p50", "Compare avg vs p50. If avg >> p50, outliers are pulling the average up."),
                Map.entry("note_tail", "Compare p99 vs p50. The bigger the gap, the worse your tail latency.")
        );
    }

    /**
     * Returns the raw sorted samples for the dashboard histogram.
     */
    public List<Long> getSortedSamples() {
        var snapshot = new ArrayList<>(samples);
        Collections.sort(snapshot);
        return snapshot;
    }

    public void reset() {
        samples.clear();
    }

    /**
     * The actual percentile math:
     * 1. Sort the list
     * 2. Find position = (percentile / 100) * (size - 1)
     * 3. Return value at that position
     */
    private long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}
