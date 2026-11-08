package com.tracepilot.pipeline;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tracepilot.pipeline")
public record PipelineProperties(boolean enabled, int publishBatchSize, int maxAttempts,
                                 Duration retryDelay, Duration processingLease) {
    public PipelineProperties {
        if (publishBatchSize < 1 || publishBatchSize > 20 || maxAttempts < 1 || maxAttempts > 10
            || retryDelay.compareTo(Duration.ofMillis(100)) < 0 || retryDelay.compareTo(Duration.ofMinutes(1)) > 0
            || processingLease.compareTo(Duration.ofMinutes(4)) < 0) {
            throw new IllegalArgumentException("Invalid pipeline batch, retry, or lease settings.");
        }
    }
}
