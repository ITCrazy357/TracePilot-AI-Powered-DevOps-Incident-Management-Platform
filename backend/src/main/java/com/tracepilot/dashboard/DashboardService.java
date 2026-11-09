package com.tracepilot.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardService {
    private final DashboardRepository repository;
    private final WorkspaceMemberRepository members;
    private final Clock clock;

    public DashboardService(DashboardRepository repository, WorkspaceMemberRepository members, Clock clock) {
        this.repository = repository;
        this.members = members;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardDtos.Dashboard get(UUID actorId, UUID workspaceId, int days) {
        members.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace membership not found."));
        var to = clock.instant();
        var lastDay = LocalDate.ofInstant(to, ZoneOffset.UTC);
        var firstDay = lastDay.minusDays(days - 1L);
        var from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        var incidents = repository.incidents(workspaceId, from, to);
        var runs = repository.runs(workspaceId, from, to);
        var daily = new ArrayList<DashboardDtos.Day>();
        for (var date = firstDay; !date.isAfter(lastDay); date = date.plusDays(1)) {
            var run = runs.getOrDefault(date, new DashboardDtos.Day(date, 0, 0, 0));
            daily.add(new DashboardDtos.Day(date, incidents.getOrDefault(date, 0L), run.successfulRuns(), run.failedRuns()));
        }
        return new DashboardDtos.Dashboard(from, to, "UTC", daily, repository.statuses(workspaceId, from, to));
    }
}
