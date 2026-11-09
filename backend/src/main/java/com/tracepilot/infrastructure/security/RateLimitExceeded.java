package com.tracepilot.infrastructure.security;

public class RateLimitExceeded extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitExceeded(long retryAfterSeconds) {
        super("Too many requests. Retry after the indicated delay.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
