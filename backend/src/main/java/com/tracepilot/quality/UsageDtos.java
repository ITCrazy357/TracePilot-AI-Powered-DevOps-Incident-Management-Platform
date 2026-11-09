package com.tracepilot.quality;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UsageDtos {
    private UsageDtos() {
    }

    public record Filters(@Size(max = 120) String model, @Pattern(regexp = "CHAT|EMBEDDING") String operation) {
    }

    public record Totals(long calls, long succeeded, long failed, long unknown, Long inputTokens, Long outputTokens,
                         long inputSamples, long outputSamples, BigDecimal knownEstimatedCostUsd, long pricedCalls) {
        public static Totals empty() {
            return new Totals(0, 0, 0, 0, 0L, 0L, 0, 0, BigDecimal.ZERO, 0);
        }
    }

    public record Day(LocalDate date, Totals usage) {
    }

    public record Model(String operation, String provider, String model, Totals usage) {
    }

    public record Dashboard(Instant from, Instant to, String timeZone, Totals totals, List<Day> daily,
                            List<Model> byModel, List<String> models) {
    }
}
