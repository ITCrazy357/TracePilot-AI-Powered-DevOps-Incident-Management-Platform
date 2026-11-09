package com.tracepilot.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tracepilot.rate-limit")
public record RateLimitProperties(boolean enabled, int authPerMinute, int sessionPerMinute,
                                  int readsPerMinute, int writesPerMinute, int expensivePerMinute,
                                  int loginPerAccount) {
    public RateLimitProperties {
        for (int limit : new int[] {authPerMinute, sessionPerMinute, readsPerMinute,
            writesPerMinute, expensivePerMinute, loginPerAccount}) {
            if (limit < 1 || limit > 100000) {
                throw new IllegalArgumentException("Rate limits must be between 1 and 100000.");
            }
        }
    }
}
