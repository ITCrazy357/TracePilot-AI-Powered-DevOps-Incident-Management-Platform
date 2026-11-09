package com.tracepilot.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() {
    }

    public record Day(LocalDate date, long incidents, long successfulRuns, long failedRuns) {
    }

    public record Status(String status, long count) {
    }

    public record Dashboard(Instant from, Instant to, String timeZone, List<Day> daily, List<Status> statuses) {
    }
}
