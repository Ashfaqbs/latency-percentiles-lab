package com.example.latencylab.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LabProperties.class)
public class MetricsConfig {

    @Bean
    public Timer requestLatencyTimer(MeterRegistry registry) {
        return Timer.builder("lab.request.latency")
                .description("Simulated API request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }
}
