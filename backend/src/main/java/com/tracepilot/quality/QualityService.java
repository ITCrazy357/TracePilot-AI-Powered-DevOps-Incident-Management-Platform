package com.tracepilot.quality;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.tracepilot.workspace.MembershipStatus;
import com.tracepilot.workspace.WorkspaceMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QualityService {
    private final QualityRepository repository;
    private final WorkspaceMemberRepository members;
    private final Clock clock;

    public QualityService(QualityRepository repository, WorkspaceMemberRepository members, Clock clock) {
        this.repository = repository;
        this.members = members;
        this.clock = clock;
    }

    // All aggregates use one snapshot; a worker completing between queries cannot skew denominators.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QualityDtos.Dashboard dashboard(UUID actorId, UUID workspaceId, int days) {
        members.findByWorkspaceIdAndUserIdAndStatus(workspaceId, actorId, MembershipStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace membership not found."));
        var to = clock.instant();
        var from = to.minus(days, ChronoUnit.DAYS);
        var analyses = repository.analyses(workspaceId, from, to);
        return new QualityDtos.Dashboard(from, to, analyses,
            repository.feedback(workspaceId, from, to, analyses.succeeded()),
            repository.reasons(workspaceId, from, to), repository.usage(workspaceId, from, to),
            repository.errors(workspaceId, from, to));
    }
}
