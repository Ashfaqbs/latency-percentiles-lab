package com.example.latencylab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lab")
public record LabProperties(
        int fastMs,
        int mediumMs,
        int slowMs,
        int timeoutMs,
        int fastWeight,
        int mediumWeight,
        int slowWeight,
        int timeoutWeight
) {}
