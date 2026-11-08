package com.tracepilot.analysis;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tracepilot.ai")
public record AiProperties(boolean enabled, String apiKey, String model, URI baseUrl, Duration timeout,
                           int maxInputCharacters, int maxOutputTokens, int maxCallsPerDay,
                           BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
    public AiProperties {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
            || timeout.compareTo(Duration.ofSeconds(60)) > 0 || maxInputCharacters < 100
            || maxInputCharacters > 32768 || maxOutputTokens < 128 || maxOutputTokens > 8192
            || maxCallsPerDay < 1 || maxCallsPerDay > 1000) {
            throw new IllegalArgumentException("Invalid AI timeout, size or daily call limit.");
        }
        if (enabled && (apiKey == null || apiKey.isBlank() || model == null
            || !model.matches("[A-Za-z0-9._:/-]{1,120}"))) {
            throw new IllegalArgumentException("Enabled AI requires an API key and an explicit model.");
        }
        boolean local = "127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost());
        if (!("https".equals(baseUrl.getScheme()) || (local && "http".equals(baseUrl.getScheme())))
            || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("AI base URL must use HTTPS; HTTP loopback is allowed for fixtures.");
        }
        if ((inputPricePerMillion != null && inputPricePerMillion.signum() < 0)
            || (outputPricePerMillion != null && outputPricePerMillion.signum() < 0)) {
            throw new IllegalArgumentException("AI token prices cannot be negative.");
        }
    }

    @Override
    public String toString() {
        return "AiProperties[enabled=" + enabled + ", model=" + model + "]";
    }
}
