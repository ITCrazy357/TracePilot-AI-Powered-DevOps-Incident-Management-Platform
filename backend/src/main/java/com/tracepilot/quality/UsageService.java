package com.tracepilot.quality;

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
public class UsageService {
    private final UsageRepository repository;
    private final WorkspaceMemberRepository members;
    private final Clock clock;

    public UsageService(UsageRepository repository, WorkspaceMemberRepository members, Clock clock) {
        this.repository = repository;
        this.members = members;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsageDtos.Dashboard get(UUID actorId, UUID workspaceId, int days, UsageDtos.Filters filters) {
        members.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace membership not found."));
        var to = clock.instant();
        var lastDay = LocalDate.ofInstant(to, ZoneOffset.UTC);
        var firstDay = lastDay.minusDays(days - 1L);
        var from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        var data = repository.read(workspaceId, from, to, filters);
        var daily = new ArrayList<UsageDtos.Day>();
        for (var date = firstDay; !date.isAfter(lastDay); date = date.plusDays(1)) {
            daily.add(data.daily().getOrDefault(date, new UsageDtos.Day(date, UsageDtos.Totals.empty())));
        }
        return new UsageDtos.Dashboard(from, to, "UTC", data.totals(), daily, data.byModel(), data.models());
    }
}
