package com.example.latencylab.service;

import com.example.latencylab.config.LabProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates realistic API latency by sleeping for a weighted-random duration.
 *
 * The idea: most requests are fast (cache hit), some are medium (DB read),
 * a few are slow (cache miss + DB), and very rarely one times out.
 * This creates the kind of latency distribution you see in production.
 */
@Service
public class LatencySimulator {

    private final LabProperties properties;

    public LatencySimulator(LabProperties properties) {
        this.properties = properties;
    }

    /**
     * Simulates work by sleeping. Returns the actual sleep duration in ms.
     * Adds +/- 20% jitter to make it more realistic.
     */
    public long simulateWork() {
        int baseDuration = pickDuration();
        // Add jitter: +/- 20% randomness
        double jitter = 0.8 + (ThreadLocalRandom.current().nextDouble() * 0.4);
        long actualDuration = Math.max(1, (long) (baseDuration * jitter));

        try {
            Thread.sleep(actualDuration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return actualDuration;
    }

    /**
     * Returns which scenario label was picked (for logging/response).
     */
    public String simulateWorkWithLabel() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        String label;
        int baseDuration;

        if (roll < properties.fastWeight()) {
            label = "fast (cache hit)";
            baseDuration = properties.fastMs();
        } else if (roll < properties.fastWeight() + properties.mediumWeight()) {
            label = "medium (DB read)";
            baseDuration = properties.mediumMs();
        } else if (roll < properties.fastWeight() + properties.mediumWeight() + properties.slowWeight()) {
            label = "slow (cache miss + DB)";
            baseDuration = properties.slowMs();
        } else {
            label = "timeout (GC pause / upstream timeout)";
            baseDuration = properties.timeoutMs();
        }

        double jitter = 0.8 + (ThreadLocalRandom.current().nextDouble() * 0.4);
        long actualMs = Math.max(1, (long) (baseDuration * jitter));

        try {
            Thread.sleep(actualMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return label + " | " + actualMs + "ms";
    }

    private int pickDuration() {
        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < properties.fastWeight()) {
            return properties.fastMs();
        } else if (roll < properties.fastWeight() + properties.mediumWeight()) {
            return properties.mediumMs();
        } else if (roll < properties.fastWeight() + properties.mediumWeight() + properties.slowWeight()) {
            return properties.slowMs();
        } else {
            return properties.timeoutMs();
        }
    }
}
